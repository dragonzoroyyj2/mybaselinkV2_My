import platform
import sys
import subprocess
import logging
import pandas as pd
import json
from concurrent.futures import ThreadPoolExecutor, as_completed
import FinanceDataReader as fdr
import numpy as np
import argparse
from datetime import datetime
import os
import matplotlib.pyplot as plt
from io import BytesIO
import base64
from sklearn.metrics.pairwise import cosine_similarity
import requests
import ssl

# === 환경 체크 ===
def check_environment():
    logger = logging.getLogger("env_check")
    logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stderr)
    formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    logger.info("=== 환경 체크 시작 ===")

    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()[0]
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")

    py_ver = sys.version
    logger.info(f"Python Version: {py_ver}")

    missing_libs = []
    for lib in ["pandas", "numpy", "FinanceDataReader", "matplotlib", "sklearn"]:
        try:
            __import__(lib)
        except ImportError:
            missing_libs.append(lib)
    if missing_libs:
        logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
    else:
        logger.info("필수 라이브러리 모두 설치됨")

    if os_name == "Windows":
        try:
            result = subprocess.run(
                ["reg", "query", r"HKLM\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64"],
                capture_output=True, text=True
            )
            if "Installed" in result.stdout:
                logger.info("Visual C++ Redistributable 설치됨")
            else:
                logger.warning("Visual C++ Redistributable 미설치")
        except Exception as e:
            logger.warning(f"C++ Redistributable 체크 실패: {e}")

    logger.info("=== 환경 체크 완료 ===\n")

check_environment()

# ==========================================================================

# 디렉토리 및 로깅 설정
script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "log", "my_log_file_new.log")
data_dir = os.path.join(script_dir, "stock_data")
os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)

logger = logging.getLogger()
logger.setLevel(logging.INFO)
formatter = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
file_handler = logging.FileHandler(log_file_path, encoding='utf-8')
file_handler.setFormatter(formatter)
logger.addHandler(file_handler)
stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setFormatter(formatter)
logger.addHandler(stream_handler)

# ==========================================================================

def fetch_fdr_with_retry(symbol, start=None, end=None):
    try:
        df = fdr.DataReader(symbol, start=start, end=end)
        if df.empty:
            logger.error(f"[Python ERR] {symbol} 데이터 조회 실패: 데이터 없음")
            sys.exit(1)
        return df
    except Exception as e:
        logger.error(f"[Python ERR] {symbol} 데이터 조회 실패: {e}")
        sys.exit(1)

def fetch_fdr_and_save(symbol):
    file_path = os.path.join(data_dir, f"{symbol}.parquet")
    today = datetime.now().strftime('%Y-%m-%d')
    try:
        if os.path.exists(file_path):
            existing_df = pd.read_parquet(file_path)
            last_date = existing_df.index.max().strftime('%Y-%m-%d')
            new_data = fetch_fdr_with_retry(symbol, start=last_date, end=today)
            if not new_data.empty and new_data.index.max() > existing_df.index.max():
                updated_df = pd.concat([existing_df, new_data[new_data.index > existing_df.index.max()]]).sort_index()
                updated_df.to_parquet(file_path)
                logger.info(f"{symbol} 데이터 업데이트 완료")
        else:
            df = fetch_fdr_with_retry(symbol)
            df.to_parquet(file_path)
            logger.info(f"{symbol} 전체 데이터 새로 저장")
    except Exception as e:
        logger.error(f"{symbol} 데이터 처리 실패: {e}")
        sys.exit(1)

def save_all_data():
    today = datetime.now().strftime('%Y-%m-%d')
    logger.info("오늘 종가 데이터 확인 중...")
    try:
        sample_data = fdr.DataReader('005930', start=today, end=today)
        if sample_data.empty or sample_data.index.max() < datetime.now().date():
            logger.error("오늘 종가 데이터가 없음")
            sys.exit(1)
    except Exception as e:
        logger.error(f"오늘 종가 확인 실패: {e}")
        sys.exit(1)

    logger.info("오늘 종가 데이터 확인됨. 모든 종목 데이터 업데이트 시작...")
    try:
        krx_list = fdr.StockListing('KRX')
        symbol_col = next((c for c in ['Symbol','Code'] if c in krx_list.columns), None)
        if not symbol_col:
            raise KeyError("KRX 리스트에서 Symbol 또는 Code 컬럼 없음")
        krx_symbols = krx_list[symbol_col].tolist()
    except Exception as e:
        logger.error(f"KRX 종목 리스트 로드 실패: {e}")
        sys.exit(1)

    for symbol in krx_symbols:
        fetch_fdr_and_save(symbol)
    logger.info("모든 종목 데이터 업데이트 완료.")

# ==========================================================================

def calculate_similarity(base_prices, compare_prices, method="cosine"):
    compare_prices = compare_prices.reindex(base_prices.index).interpolate(method='linear')
    if compare_prices.isnull().any():
        return None
    if method=="cosine":
        base_norm = (base_prices - np.mean(base_prices)) / np.std(base_prices)
        compare_norm = (compare_prices - np.mean(compare_prices)) / np.std(compare_prices)
        sim = cosine_similarity(base_norm.values.reshape(1,-1), compare_norm.values.reshape(1,-1)).item()
    elif method=="pearson":
        sim = base_prices.corr(compare_prices)
    elif method=="euclidean":
        sim = 1 / (1 + np.linalg.norm(base_prices - compare_prices))
    elif method=="slope":
        sim = 1 / (1 + np.mean(np.abs(np.gradient(base_prices) - np.gradient(compare_prices))))
    elif method=="dtw":
        try:
            from dtaidistance import dtw
            sim = 1 / (1 + dtw.distance(base_prices.values, compare_prices.values))
        except ImportError:
            logger.error("dtaidistance 설치 필요 (pip install dtaidistance)")
            sys.exit(1)
    else:
        sim = None
    return sim

def generate_base64_chart(df, title):
    try:
        plt.figure(figsize=(6,3))
        plt.plot(df.index, df['Close'], label=title)
        plt.title(title)
        plt.xlabel('Date')
        plt.ylabel('Close')
        plt.legend()
        buf = BytesIO()
        plt.savefig(buf, format='png', dpi=150, bbox_inches='tight')
        plt.close()
        buf.seek(0)
        return base64.b64encode(buf.read()).decode('utf-8')
    except Exception as e:
        logger.error(f"{title} 차트 생성 실패: {e}")
        sys.exit(1)

def find_similar_chart_parallel(base_symbol, start_date, end_date, method="cosine", n_similar_stocks=5, max_workers=6):
    save_all_data()
    try:
        krx_list = fdr.StockListing('KRX')
        symbol_col = next((c for c in ['Symbol','Code'] if c in krx_list.columns), None)
        name_col = 'Name'
        krx_symbols = krx_list[symbol_col].tolist()
        krx_name_map = krx_list.set_index(symbol_col)[name_col].to_dict()
    except Exception as e:
        logger.error(f"KRX 리스트 로드 실패: {e}")
        sys.exit(1)

    try:
        base_data = pd.read_parquet(os.path.join(data_dir, f"{base_symbol}.parquet"))
        base_data = base_data.loc[start_date:end_date]
        base_close = base_data['Close']
    except Exception as e:
        logger.error(f"{base_symbol} 데이터 로드 실패: {e}")
        sys.exit(1)

    results = []

    def process_symbol(symbol):
        if symbol == base_symbol: return None
        try:
            file_path = os.path.join(data_dir, f"{symbol}.parquet")
            if not os.path.exists(file_path): return None
            comp_data = pd.read_parquet(file_path).loc[start_date:end_date]
            sim = calculate_similarity(base_close, comp_data['Close'], method=method)
            chart_b64 = generate_base64_chart(comp_data, f"{symbol} 차트")
            if sim is not None:
                return {"ticker": symbol, "name": krx_name_map.get(symbol,"알 수 없음"), "similarity": sim, "chart": chart_b64}
        except Exception as e:
            logger.error(f"{symbol} 처리 실패: {e}")
            sys.exit(1)
        return None

    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(process_symbol, sym) for sym in krx_symbols]
        for future in as_completed(futures):
            res = future.result()
            if res:
                results.append(res)

    results.sort(key=lambda x: x['similarity'], reverse=True)
    return results[:n_similar_stocks]

# ==========================================================================

def main():
    parser = argparse.ArgumentParser(description="주가 차트 유사도 분석")
    parser.add_argument("--base_symbol", type=str, required=True)
    parser.add_argument("--start_date", type=str, default="2023-01-01")
    parser.add_argument("--end_date", type=str, default=datetime.now().strftime('%Y-%m-%d'))
    parser.add_argument("--method", type=str, default="cosine", help="cosine, dtw, pearson, euclidean, slope")
    parser.add_argument("--n_similar", type=int, default=5)
    parser.add_argument("--threads", type=int, default=6)
    args = parser.parse_args()

    results = find_similar_chart_parallel(
        args.base_symbol, args.start_date, args.end_date,
        method=args.method, n_similar_stocks=args.n_similar,
        max_workers=args.threads
    )
    if results:
        print(json.dumps({"base_symbol": args.base_symbol, "similar_stocks": results}, ensure_ascii=False, indent=2))
    else:
        logger.error("유사 종목 없음")
        sys.exit(1)

if __name__=="__main__":
    main()