# -*- coding: utf-8 -*-
"""
📉 find_last_close_downward.py (v2.2 이동평균선 5/20/60 추가)
------------------------------------------------------
✅ stdout = JSON 전용 출력
✅ stderr = 모든 로그 / 예외 / 404 오류
✅ Yahoo 404 / connection error 자동 무시
✅ Spring Boot 연동 100% 완전 정상 작동
✅ Matplotlib/Mplfinance 한글 폰트 설정 추가
✅ 결과 필드명: ticker/name/streak 으로 통일
✅ Pathlib 경로 설정 오류 수정
✅ 차트 모드: 일봉 + 이동평균선 (5, 20, 60일) 적용
✅ 한국식 양봉(빨강), 음봉(파랑) 색상 적용
------------------------------------------------------
"""

import os
import sys
import json
import logging
import argparse
import pandas as pd
import FinanceDataReader as fdr
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import mplfinance as mpf
import io, base64, traceback
from datetime import datetime, timedelta
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed


# =====================================================
# 📁 경로 설정
# =====================================================

BASE_DIR = Path(__file__).resolve().parents[2]
DATA_DIR = BASE_DIR / "data" / "stock_data"
LIST_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json"
LOG_DIR = BASE_DIR / "log"
LOG_FILE = LOG_DIR / "find_last_close_downward.log"


# =====================================================
# ⚙️ 환경 세팅 및 한글 폰트 설정
# =====================================================

LOG_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR.mkdir(parents=True, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding="utf-8"),
        logging.StreamHandler(sys.stderr)
    ]
)


def set_korean_font():
    """운영체제에 따라 한글 폰트를 설정하고, mplfinance 스타일에도 적용될 수 있도록 설정합니다."""
    if sys.platform.startswith('win'):
        font_family = 'Malgun Gothic'
    elif sys.platform.startswith('darwin'):
        font_family = 'AppleGothic'
    else:
        # 리눅스 환경 등 기본 설정 (필요시 'NanumGothic' 등 설치 필요)
        font_family = 'NanumGothic'
        
    plt.rc('font', family=font_family)
    plt.rcParams['axes.unicode_minus'] = False
    
    global MPLFINANCE_FONT
    MPLFINANCE_FONT = font_family


set_korean_font()
MPLFINANCE_FONT = plt.rcParams['font.family'] if plt.rcParams['font.family'] else 'sans-serif'


class StdoutFilter:
    def write(self, text):
        pass

    def flush(self):
        pass


sys.stdout = open(os.devnull, "w")


def safe_print_json(data):
    sys.__stdout__.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    sys.__stdout__.flush()


# =====================================================
# 📊 유틸 함수
# =====================================================

def load_listing():
    if not LIST_FILE.exists():
        raise FileNotFoundError(f"종목 리스트 파일 없음: {LIST_FILE}")
    with open(LIST_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def get_stock_name(symbol):
    """종목 코드를 기반으로 종목명을 찾습니다."""
    try:
        items = load_listing()
        for item in items:
            code = item.get("Code") or item.get("code")
            if code == symbol:
                return item.get("Name") or item.get("name")
        return symbol
    except Exception:
        return symbol


def count_consecutive_down(df):
    close = df["Close"].tolist()
    cnt = 0

    for i in range(len(close) - 1, 0, -1):
        if close[i] < close[i - 1]:
            cnt += 1
        else:
            break

    return cnt


# =====================================================
# 📈 차트 모드 (Mplfinance 적용)
# =====================================================

def generate_chart(symbol, start_date=None, end_date=None):
    try:
        df = fdr.DataReader(symbol, start=start_date, end=end_date)
        stock_name = get_stock_name(symbol)

        if df is None or df.empty:
            safe_print_json({"error": f"{stock_name} ({symbol}) 데이터 없음"})
            return
        
        mc = mpf.make_marketcolors(
            up='red',
            down='blue',
            edge='inherit',
            wick='inherit',
            volume='inherit'
        )

        s = mpf.make_mpf_style(
            base_mpf_style='yahoo',
            marketcolors=mc,
            rc={'font.family': MPLFINANCE_FONT}
        )
        
        fig, axes = mpf.plot(
            df,
            type="candle",
            mav=[5, 20, 60], # ⭐ 5일, 20일, 60일 이동평균선 추가
            volume=True,
            style=s,
            title=f"[{stock_name} ({symbol})] 주가 및 거래량 차트 (MA: 5, 20, 60)", # ⭐ 제목 업데이트
            figsize=(12, 8),
            returnfig=True
        )
        
        # 날짜 포맷 설정 (axes는 캔들 차트의 Axes, mplfinance 0.12.0 이후는 axes[0] 사용)
        import matplotlib.dates as mdates
        axes[0].xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))
        plt.xticks(rotation=45, ha='right')

        buf = io.BytesIO()
        plt.savefig(buf, format="png", bbox_inches="tight")
        buf.seek(0)
        b64 = base64.b64encode(buf.read()).decode("utf-8")
        buf.close()
        plt.close(fig)

        safe_print_json({"image_data": b64})
        sys.exit(0)

    except Exception as e:
        logging.error(f"[ERROR] 차트 생성 실패: {e}")
        traceback.print_exc(file=sys.stderr)
        safe_print_json({"error": f"차트 생성 실패: {e}"})
        sys.exit(1)


# =====================================================
# 📉 개별 종목 분석
# =====================================================
def analyze_symbol(item, start_date, end_date):
    code = item.get("Code") or item.get("code")
    name = item.get("Name") or item.get("name")
    path = DATA_DIR / f"{code}.parquet"

    try:
        if path.exists():
            df = pd.read_parquet(path)
        else:
            df = fdr.DataReader(code)
            df.to_parquet(path)

        df = df.loc[(df.index >= start_date) & (df.index <= end_date)]
        if df.empty:
            return None

        d = count_consecutive_down(df)
        if d >= 3:
            return {"ticker": code, "name": name, "streak": d}

        return None

    except Exception as e:
        logging.error(f"[ERROR] {code} {name} 분석 실패: {e}")
        return None

# =====================================================
# 🚀 전체 분석
# =====================================================

def run_analysis(start_date, end_date, topN, workers):
    try:
        logging.info("[LOG] 연속 하락 종목 분석 시작...")

        items = load_listing()
        total = len(items)
        logging.info(f"[LOG] 종목 수: {total}")

        results = []
        done = 0
        last_p = 0

        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = [ex.submit(analyze_symbol, i, start_date, end_date) for i in items]

            for f in as_completed(futs):
                r = f.result()
                done += 1

                if r:
                    results.append(r)

                pct = done / total * 100
                if pct - last_p >= 0.5 or done == total:
                    logging.info(f"[PROGRESS] {pct:.2f}")
                    last_p = pct

        results = sorted(results, key=lambda x: x["streak"], reverse=True)[:topN]

        logging.info(f"[LOG] 분석 완료, {len(results)}개 종목 발견")
        safe_print_json(results)
        sys.exit(0)

    except Exception as e:
        logging.error(f"[ERROR] 분석 중 예외 발생: {e}")
        traceback.print_exc(file=sys.stderr)
        safe_print_json({"error": str(e)})
        sys.exit(1)


# =====================================================
# 🏁 main
# =====================================================

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", required=False)
    parser.add_argument("--base_symbol", required=False)
    parser.add_argument("--start_date", required=False, default=(datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d"))
    parser.add_argument("--end_date", required=False, default=datetime.now().strftime("%Y-%m-%d"))
    parser.add_argument("--topN", type=int, default=100)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    if args.symbol:
        generate_chart(args.symbol, args.start_date, args.end_date)
    elif args.base_symbol == "ALL":
        run_analysis(args.start_date, args.end_date, args.topN, args.workers)
    else:
        # ⭐ main 함수의 누락된 else 부분 추가
        safe_print_json({"error": "올바른 인자 필요: --symbol 또는 --base_symbol ALL"})
        sys.exit(2)
