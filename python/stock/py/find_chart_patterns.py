# -*- coding: utf-8 -*-
"""
캐시(.parquet) 기반 차트 패턴 스캐너
- 지원 패턴: head_and_shoulders, inverse_head_and_shoulders, double_top, double_bottom, cup_and_handle(간단 휴리스틱)
- 입력: --start / --end / --pattern / --topN / --workers
- 출력: JSON 배열 [{symbol,name,patterns:[...]}, ...]
- 절대 다운로드/업데이트 없음 (데이터 없으면 에러 JSON 후 종료)
"""

import os
import sys
import json
import logging
import argparse
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

import pandas as pd
import numpy as np

# ---------------------------------------------------
# 콘솔/로그 세팅 (UTF-8)
# ---------------------------------------------------
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PY_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))  # ...\python
DATA_DIR = os.path.join(PY_ROOT, "stock_data")
LOG_DIR = os.path.join(PY_ROOT, "log")
os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(LOG_DIR, exist_ok=True)

LOG_PATH = os.path.join(LOG_DIR, "my_log_file.log")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[logging.FileHandler(LOG_PATH, mode="a", encoding="utf-8")]
)
logger = logging.getLogger(__name__)


def error_exit(msg: str, code: int = 1):
    logger.error(msg)
    print(json.dumps({"error": msg}, ensure_ascii=False), file=sys.stderr)
    sys.exit(code)


def load_listing():
    listing_path = os.path.join(DATA_DIR, "stock_listing.json")
    if not os.path.exists(listing_path):
        error_exit(f"종목 목록 파일이 없습니다: {listing_path}")
    try:
        df = pd.read_json(listing_path, orient="records")
        if "Code" in df.columns and "code" not in df.columns:
            df = df.rename(columns={"Code": "code"})
        if "Name" in df.columns and "name" not in df.columns:
            df = df.rename(columns={"Name": "name"})
        if "code" not in df.columns:
            error_exit("stock_listing.json 에 'code' 컬럼이 없습니다.")
        if "name" not in df.columns:
            df["name"] = ""
        return df[["code","name"]]
    except Exception as e:
        error_exit(f"종목 목록 파싱 실패: {e}")


def load_parquet(symbol: str) -> pd.DataFrame:
    path = os.path.join(DATA_DIR, f"{symbol}.parquet")
    if not os.path.exists(path):
        return None
    try:
        df = pd.read_parquet(path)
        if not isinstance(df.index, pd.DatetimeIndex):
            if "Date" in df.columns:
                df["Date"] = pd.to_datetime(df["Date"])
                df = df.set_index("Date")
            else:
                return None
        return df
    except Exception as e:
        logger.warning(f"{symbol} 파케이 로드 실패: {e}")
        return None


def slice_close(df: pd.DataFrame, start: str, end: str) -> pd.Series:
    try:
        part = df.loc[start:end].sort_index()
        return part["Close"].astype(float)
    except Exception:
        return pd.Series(dtype=float)


# -------------------------------
# 간단 휴리스틱 패턴 검출기
# -------------------------------
def local_extrema(series: pd.Series, window:int=3):
    """아주 단순한 로컬 최대/최소 탐지 (중간값이 이웃보다 크거나/작으면 peak/trough)"""
    s = series.values
    peaks, troughs = [], []
    for i in range(1, len(s)-1):
        if s[i] > s[i-1] and s[i] > s[i+1]:
            peaks.append(i)
        if s[i] < s[i-1] and s[i] < s[i+1]:
            troughs.append(i)
    return peaks, troughs


def detect_double_top(close: pd.Series) -> bool:
    peaks, _ = local_extrema(close)
    if len(peaks) < 2: return False
    # 두 개 피크 높이가 유사(±3%), 사이에 의미 있는 하락
    for i in range(len(peaks)-1):
        p1, p2 = peaks[i], peaks[i+1]
        v1, v2 = close.iloc[p1], close.iloc[p2]
        if v1 == 0: continue
        if abs(v1 - v2)/v1 <= 0.03:
            mid_min = close.iloc[p1:p2+1].min()
            if mid_min < min(v1, v2) * 0.95:
                return True
    return False


def detect_double_bottom(close: pd.Series) -> bool:
    _, troughs = local_extrema(close)
    if len(troughs) < 2: return False
    for i in range(len(troughs)-1):
        t1, t2 = troughs[i], troughs[i+1]
        v1, v2 = close.iloc[t1], close.iloc[t2]
        if v1 == 0: continue
        if abs(v1 - v2)/max(v1,1e-9) <= 0.03:
            mid_max = close.iloc[t1:t2+1].max()
            if mid_max > max(v1, v2) * 1.05:
                return True
    return False


def detect_head_and_shoulders(close: pd.Series) -> bool:
    peaks, _ = local_extrema(close)
    # 최소 3개의 피크: 좌-머리-우
    if len(peaks) < 3: return False
    for i in range(len(peaks)-2):
        l, h, r = peaks[i], peaks[i+1], peaks[i+2]
        if close.iloc[h] > close.iloc[l] * 1.03 and close.iloc[h] > close.iloc[r] * 1.03:
            # 양어깨 높이 유사
            if abs(close.iloc[l] - close.iloc[r])/max(close.iloc[h],1e-9) < 0.1:
                return True
    return False


def detect_inverse_head_and_shoulders(close: pd.Series) -> bool:
    _, troughs = local_extrema(close)
    if len(troughs) < 3: return False
    for i in range(len(troughs)-2):
        l, h, r = troughs[i], troughs[i+1], troughs[i+2]
        if close.iloc[h] < close.iloc[l] * 0.97 and close.iloc[h] < close.iloc[r] * 0.97:
            if abs(close.iloc[l] - close.iloc[r])/max(close.iloc[h],1e-9) < 0.1:
                return True
    return False


def detect_cup_and_handle(close: pd.Series) -> bool:
    # 아주 단순: 완만한 U자 후 소폭 되돌림(핸들)
    arr = close.values
    if len(arr) < 40:
        return False
    # U자: 처음 대비 중간 저점이 충분히 낮고, 끝단이 회복
    left = arr[:len(arr)//2]
    right = arr[len(arr)//2:]
    mid_min = np.min(arr)
    if mid_min > np.percentile(arr, 25):
        return False
    if right[-1] < np.median(right):
        return False
    # 핸들: 마지막 10% 구간에서 2~8% 되돌림 흔적
    tail = arr[-max(5, len(arr)//10):]
    if len(tail) >= 5:
        drop = (np.max(tail) - tail[-1]) / max(np.max(tail), 1e-9)
        if 0.02 <= drop <= 0.08:
            return True
    return False


def match_pattern(close: pd.Series, pattern: str) -> bool:
    if len(close) < 30:
        return False
    if pattern == "double_top":
        return detect_double_top(close)
    if pattern == "double_bottom":
        return detect_double_bottom(close)
    if pattern == "head_and_shoulders":
        return detect_head_and_shoulders(close)
    if pattern == "inverse_head_and_shoulders":
        return detect_inverse_head_and_shoulders(close)
    if pattern == "cup_and_handle":
        return detect_cup_and_handle(close)
    return False


def scan_symbol(symbol: str, name: str, start: str, end: str, pattern: str):
    df = load_parquet(symbol)
    if df is None:
        return None
    close = slice_close(df, start, end)
    if close.empty or close.isna().any():
        return None
    try:
        ok = match_pattern(close, pattern)
        if ok:
            return {"symbol": symbol, "name": name, "patterns": [pattern]}
    except Exception:
        return None
    return None


def main():
    parser = argparse.ArgumentParser(description="캐시 기반 차트 패턴 스캐너")
    parser.add_argument("--start", dest="start", required=True, help="시작일 YYYY-MM-DD")
    parser.add_argument("--end", dest="end", required=True, help="종료일 YYYY-MM-DD")
    parser.add_argument("--pattern", required=True, choices=[
        "head_and_shoulders", "inverse_head_and_shoulders",
        "double_top", "double_bottom", "cup_and_handle"
    ])
    parser.add_argument("--topN", type=int, default=10)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    start = args.start
    end = args.end
    pattern = args.pattern
    topN = max(1, args.topN)
    workers = max(1, args.workers)

    logger.info(f"[PROGRESS] 1 환경 점검(캐시 확인)")
    listing = load_listing()
    codes = listing["code"].astype(str).tolist()
    name_map = dict(zip(listing["code"].astype(str), listing["name"].astype(str)))

    total = len(codes)
    logger.info(f"[PROGRESS] 5 스캔 준비 (대상 {total} 종목)")

    results = []
    done = 0
    step_log_every = max(1, total // 10)

    with ThreadPoolExecutor(max_workers=workers) as ex:
        futures = {ex.submit(scan_symbol, c, name_map.get(c, ""), start, end, pattern): c for c in codes}
        for fut in as_completed(futures):
            done += 1
            r = fut.result()
            if r:
                results.append(r)
            if done % step_log_every == 0:
                logger.info(f"[PROGRESS] 진행률 {int(done/total*100)}%")

    # 간단 정렬: 최근 변동성(표준편차) 큰 순으로 정렬해서 상위 N (임의 스코어)
    def score(item):
        df = load_parquet(item["symbol"])
        if df is None: return 0.0
        close = slice_close(df, start, end)
        if close.empty: return 0.0
        return float(np.std(close.values[-min(60, len(close)):]))
    results.sort(key=score, reverse=True)
    results = results[:topN]

    logger.info(f"[PROGRESS] 100 완료 (탐지 {len(results)}건)")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        error_exit(f"스크립트 실행 중 오류: {e}")
