# -*- coding: utf-8 -*-
"""
캐시(.parquet) 기반 유사 종목 분석 / 개별 차트 생성 스크립트
- 절대 다운로드/업데이트 없음 (데이터 없으면 에러 JSON 후 종료)
- 데이터 위치: ./stock_data
- 종목목록: stock_data/stock_listing.json (code, name 필드 필요)
- 로그: ./log/my_log_file.log (UTF-8)
"""

import os
import sys
import json
import logging
import argparse
from datetime import datetime
from io import BytesIO
import base64

import pandas as pd
import numpy as np
from sklearn.metrics.pairwise import cosine_similarity
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

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
DATA_DIR = os.path.join(SCRIPT_DIR, "stock_data")
LOG_DIR = os.path.join(SCRIPT_DIR, "log")
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
    """에러 메시지를 JSON으로 stderr에 기록하고 종료"""
    logger.error(msg)
    print(json.dumps({"error": msg}, ensure_ascii=False), file=sys.stderr)
    sys.exit(code)


def load_listing():
    listing_path = os.path.join(DATA_DIR, "stock_listing.json")
    if not os.path.exists(listing_path):
        error_exit(f"종목 목록 파일이 없습니다: {listing_path}")
    try:
        df = pd.read_json(listing_path, orient="records")
        # 필드 표준화: code, name
        if "Code" in df.columns and "code" not in df.columns:
            df = df.rename(columns={"Code": "code"})
        if "Name" in df.columns and "name" not in df.columns:
            df = df.rename(columns={"Name": "name"})
        if "code" not in df.columns:
            error_exit("stock_listing.json 에 'code' 컬럼이 없습니다.")
        if "name" not in df.columns:
            # name 없으면 임시 채움
            df["name"] = ""
        return df[["code", "name"]]
    except Exception as e:
        error_exit(f"종목 목록 파일 파싱 실패: {e}")


def load_parquet(symbol: str) -> pd.DataFrame:
    path = os.path.join(DATA_DIR, f"{symbol}.parquet")
    if not os.path.exists(path):
        return None
    try:
        df = pd.read_parquet(path)
        # 인덱스가 DatetimeIndex 보장
        if not isinstance(df.index, pd.DatetimeIndex):
            if "Date" in df.columns:
                df["Date"] = pd.to_datetime(df["Date"])
                df = df.set_index("Date")
            else:
                # 불가
                return None
        return df
    except Exception as e:
        logger.warning(f"{symbol} 파케이 로드 실패: {e}")
        return None


def slice_close(df: pd.DataFrame, start: str, end: str) -> pd.Series:
    """날짜 구간 슬라이스 후 Close 시리즈 반환(정렬 포함)"""
    try:
        part = df.loc[start:end].sort_index()
        s = part["Close"].astype(float)
        return s
    except Exception:
        return pd.Series(dtype=float)


def normalize(s: pd.Series) -> np.ndarray:
    """표준화 벡터 (평균 0, 표준편차 1). 분산 0 방지."""
    arr = s.values.astype(float)
    if arr.size == 0:
        return None
    std = np.std(arr)
    if std == 0:
        return None
    return (arr - np.mean(arr)) / std


def plot_two(base: pd.Series, base_label: str, cmp: pd.Series, cmp_label: str,
             start: str, end: str) -> str:
    """두 종목 Close 차트 그려 Base64 반환"""
    plt.figure(figsize=(12,6))
    plt.plot(base.index, base.values, label=f"{base_label}", linewidth=2.5)
    plt.plot(cmp.index, cmp.values, label=f"{cmp_label}", linewidth=1.6)
    plt.title(f"{base_label} vs {cmp_label} ({start} ~ {end})")
    plt.xlabel("날짜")
    plt.ylabel("종가")
    plt.grid(True)
    plt.legend()
    buf = BytesIO()
    plt.tight_layout()
    plt.savefig(buf, format="png")
    plt.close()
    buf.seek(0)
    return base64.b64encode(buf.read()).decode("utf-8")


def find_similar(base_symbol: str, start: str, end: str, n: int):
    # 1) 기준 종목 데이터 확인
    base_df = load_parquet(base_symbol)
    if base_df is None:
        error_exit(f"종목({base_symbol}) 데이터가 없습니다. 먼저 종목 데이터를 업데이트하세요.")
    base_close = slice_close(base_df, start, end)
    if base_close.empty:
        error_exit(f"종목({base_symbol})의 지정 구간 데이터가 비어 있습니다. 기간을 다시 지정하세요.")

    base_norm = normalize(base_close)
    if base_norm is None:
        error_exit(f"종목({base_symbol})의 표준화가 불가능합니다. 데이터가 일정하거나 결측입니다.")

    # 2) 전체 종목 목록 로드
    listing = load_listing()
    codes = listing["code"].astype(str).tolist()
    name_map = dict(zip(listing["code"].astype(str), listing["name"].astype(str)))

    # 3) 유사도 계산
    sims = []
    total = len(codes)
    step_log_every = max(1, total // 10)
    for i, sym in enumerate(codes, 1):
        if sym == base_symbol:
            continue
        df = load_parquet(sym)
        if df is None:
            continue
        s = slice_close(df, start, end)
        # 인덱스 정합(공통 날짜 교집합)
        idx = base_close.index.intersection(s.index)
        if len(idx) < max(30, int(len(base_close)*0.5)):  # 최소 길이
            continue
        s2 = s.reindex(idx)
        base2 = base_close.reindex(idx)
        if s2.isna().any() or base2.isna().any():
            continue
        v2 = normalize(s2)
        v1 = normalize(base2)
        if v1 is None or v2 is None:
            continue
        sim = float(cosine_similarity(v1.reshape(1,-1), v2.reshape(1,-1))[0][0])
        sims.append({"ticker": sym, "name": name_map.get(sym, ""), "cosine_similarity": sim})

        if i % step_log_every == 0:
            logger.info(f"[PROGRESS] 유사도 계산 {int(i/total*100)}%")

    sims.sort(key=lambda x: x["cosine_similarity"], reverse=True)
    return sims[:max(1, n)]


def main():
    parser = argparse.ArgumentParser(description="캐시 기반 유사 종목 분석/차트")
    parser.add_argument("--base_symbol", required=True, help="기준 종목 코드")
    parser.add_argument("--start_date", default="2024-01-01", help="시작일 YYYY-MM-DD")
    parser.add_argument("--end_date", default=datetime.now().strftime("%Y-%m-%d"), help="종료일 YYYY-MM-DD")
    parser.add_argument("--n_similar", type=int, default=5, help="유사 상위 N")
    parser.add_argument("--compare_symbol", help="(옵션) 단일 비교 종목 차트")
    args = parser.parse_args()

    base_symbol = str(args.base_symbol)
    start = args.start_date
    end = args.end_date

    # 단일 차트 모드
    if args.compare_symbol:
        cmp_symbol = str(args.compare_symbol)
        base_df = load_parquet(base_symbol)
        if base_df is None:
            error_exit(f"종목({base_symbol}) 데이터가 없습니다. 먼저 종목 데이터를 업데이트하세요.")
        cmp_df = load_parquet(cmp_symbol)
        if cmp_df is None:
            error_exit(f"종목({cmp_symbol}) 데이터가 없습니다. 먼저 종목 데이터를 업데이트하세요.")

        base_close = slice_close(base_df, start, end)
        cmp_close  = slice_close(cmp_df, start, end)
        if base_close.empty or cmp_close.empty:
            error_exit("차트 생성 대상 구간 데이터가 비어 있습니다. 기간을 다시 지정하세요.")

        # 공통 구간으로 맞추기
        idx = base_close.index.intersection(cmp_close.index)
        if len(idx) < 5:
            error_exit("두 종목의 공통 구간이 너무 짧습니다.")
        base_close = base_close.reindex(idx)
        cmp_close  = cmp_close.reindex(idx)

        img = plot_two(base_close, base_symbol, cmp_close, cmp_symbol, start, end)
        print(json.dumps({"image_data": img}, ensure_ascii=False))
        return

    # 유사도 모드
    logger.info("[PROGRESS] 1 환경 점검(캐시 확인)")
    logger.info("[PROGRESS] 10 유사도 계산 준비")
    result = find_similar(base_symbol, start, end, args.n_similar)
    logger.info(f"[PROGRESS] 100 완료 (결과 {len(result)}건)")
    print(json.dumps({"base_symbol": base_symbol, "similar_stocks": result}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        error_exit(f"스크립트 실행 중 오류: {e}")
