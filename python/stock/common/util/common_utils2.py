# D:\project\dev_boot_project\workspace\MyBaseLink\python\stock\common\util\common_utils.py

import os
import sys
import logging
import json
import platform
import ssl
import urllib3
import pickle
from datetime import datetime, timedelta
import hashlib
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
from matplotlib import rc
import FinanceDataReader as fdr
from http.client import RemoteDisconnected
from urllib3.exceptions import MaxRetryError, SSLError as Urllib3SSLError
from requests.exceptions import ConnectionError, SSLError as RequestsSSLError
from io import BytesIO
import base64
import requests

# ========================================================================
# 환경 설정 및 로깅
# ========================================================================
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except (AttributeError, ValueError):
        pass

script_dir = os.path.dirname(os.path.abspath(__file__))
log_file_path = os.path.join(script_dir, "log", "my_log_file.log")
data_dir = os.path.join(script_dir, "stock_data")
chart_cache_dir = os.path.join(data_dir, "charts")

os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
os.makedirs(data_dir, exist_ok=True)
os.makedirs(chart_cache_dir, exist_ok=True)

def setup_logging():
    """로깅 설정을 초기화하고 로거를 반환합니다."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s",
                        handlers=[logging.FileHandler(log_file_path, mode='a', encoding='utf-8')])
    logging.getLogger('fdr.reader').setLevel(logging.CRITICAL)
    return logging.getLogger()

logger = setup_logging()

def flush_log():
    """로그 버퍼를 강제로 비워 파일에 기록합니다."""
    for handler in logger.handlers:
        handler.flush()
    logging.shutdown()

def set_korean_font():
    """Matplotlib에서 한글이 깨지지 않도록 폰트를 설정합니다."""
    font_name = None
    if platform.system() == 'Windows':
        font_path = 'C:/Windows/Fonts/malgun.ttf'
        if os.path.exists(font_path):
            font_name = fm.FontProperties(fname=font_path).get_name()
    elif platform.system() == 'Darwin':
        font_name = 'AppleGothic'
    else:
        fonts = fm.findSystemFonts(fontpaths=None, fontext='ttf')
        for font in fonts:
            if 'nanum' in os.path.basename(font).lower():
                font_name = fm.FontProperties(fname=font).get_name()
                break
    if font_name:
        rc('font', family=font_name)
    else:
        rc('font', family='DejaVu Sans')
    plt.rcParams['axes.unicode_minus'] = False

def check_environment(required_libs):
    """스크립트 실행 환경을 확인합니다."""
    logger.info("=== 환경 체크 시작 ===")
    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")
    logger.info(f"Python Version: {sys.version}")

    missing_libs = []
    for lib in required_libs:
        try:
            __import__(lib)
        except ImportError:
            missing_libs.append(lib)
    if missing_libs:
        logger.warning(f"설치되지 않은 라이브러리: {', '.join(missing_libs)}")
        raise ImportError(f"다음 라이브러리를 설치해야 합니다: {', '.join(missing_libs)}")
    else:
        logger.info("필수 라이브러리 모두 설치됨")
    logger.info("=== 환경 체크 완료 ===\n")
    flush_log()

def fetch_fdr_with_retry_with_cache(symbol, start=None, end=None):
    """캐시를 사용하여 FinanceDataReader에서 주식 데이터를 안전하게 가져옵니다."""
    cache_key = f"{symbol}_{start}_{end}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.pkl"
    cache_path = os.path.join(data_dir, cache_filename)

    if os.path.exists(cache_path):
        if (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))) < timedelta(days=1):
            try:
                with open(cache_path, 'rb') as f:
                    logger.info(f"캐시된 데이터 로드: {symbol}")
                    return pickle.load(f)
            except Exception:
                logger.error(f"캐시 파일 손상. 삭제 후 재시도: {cache_path}")
                os.remove(cache_path)

    try:
        logger.info(f"FinanceDataReader에서 데이터 가져오기 시작: {symbol}")
        df = fdr.DataReader(symbol, start=start, end=end)
        if not df.empty:
            with open(cache_path, 'wb') as f:
                pickle.dump(df, f)
            logger.info(f"데이터 가져오기 및 캐싱 완료: {symbol}")
        return df
    except (RemoteDisconnected, ssl.SSLError, Urllib3SSLError, MaxRetryError, ConnectionError, RequestsSSLError) as e:
        error_msg = f"통신(네트워크/SSL) 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        raise SystemExit(error_msg)  # 오류 발생 시 SystemExit 예외 발생
    except Exception as e:
        error_msg = f"일반 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None


def generate_chart_with_cache(symbol, stock_name, df, start_date, end_date):
    """주식 차트를 생성하고 캐시합니다."""
    cache_key = f"{symbol}_{start_date}_{end_date}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.png"
    cache_path = os.path.join(chart_cache_dir, cache_filename)
    if os.path.exists(cache_path):
        with open(cache_path, 'rb') as f:
            img_base64 = base64.b64encode(f.read()).decode()
        return img_base64
    try:
        set_korean_font()
        plt.figure(figsize=(10, 5))
        plt.plot(df.index, df['Close'], label=stock_name)
        plt.title(f"{stock_name} 종가 차트 ({start_date} ~ {end_date})")
        plt.xlabel("날짜")
        plt.ylabel("종가")
        plt.grid(True)
        plt.tight_layout()
        buffer = BytesIO()
        plt.savefig(buffer, format='png')
        plt.savefig(cache_path, format='png')
        plt.close()
        buffer.seek(0)
        img_base64 = base64.b64encode(buffer.read()).decode()
        return img_base64
    except Exception as e:
        error_msg = f"차트 생성 중 오류 발생: {e}"
        print(json.dumps({"error": error_msg}), file=sys.stderr)
        logger.error(error_msg)
        return None

def get_stock_listing_from_cache():
    """캐시된 KRX 상장 종목 목록을 가져옵니다."""
    cache_path_pkl = os.path.join(data_dir, "stock_listing.pkl")
    
    if not os.path.exists(cache_path_pkl):
        logger.error(f"종목 목록 파일이 존재하지 않습니다: {cache_path_pkl}")
        return None
    
    try:
        with open(cache_path_pkl, 'rb') as f:
            krx = pickle.load(f)
        
        # 'Code' 열이 존재하면 'code'로 변경
        if 'code' not in krx.columns and 'Code' in krx.columns:
            krx.rename(columns={'Code': 'code'}, inplace=True)
        # 'Name' 열이 존재하면 'name'으로 변경
        if 'name' not in krx.columns and 'Name' in krx.columns:
            krx.rename(columns={'Name': 'name'}, inplace=True)

        if 'code' not in krx.columns:
            logger.error(f"피클 파일에 'code' 열이 없습니다. 사용 가능한 열: {krx.columns.tolist()}")
            return None
        if 'name' not in krx.columns:
            logger.error(f"피클 파일에 'name' 열이 없습니다. 사용 가능한 열: {krx.columns.tolist()}")
            return None

        return krx
    except Exception as e:
        logger.error(f"종목 목록 파일 로드 실패: {e}")
        return None

# SSL 설정
ssl._create_default_https_context = ssl._create_unverified_context
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)