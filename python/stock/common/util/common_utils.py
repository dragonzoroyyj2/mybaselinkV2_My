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
# ✅ 프로젝트 루트 경로 고정
# ========================================================================
BASE_DIR = r"D:\project\dev_boot_project\workspace\MyBaseLink\python"
LOG_DIR = os.path.join(BASE_DIR, "log")
DATA_DIR = os.path.join(BASE_DIR, "stock_data")
CHART_CACHE_DIR = os.path.join(DATA_DIR, "charts")

os.makedirs(LOG_DIR, exist_ok=True)
os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(CHART_CACHE_DIR, exist_ok=True)

# ✅ 하위 스크립트 호환용 (legacy alias)
data_dir = DATA_DIR
chart_cache_dir = CHART_CACHE_DIR

# ========================================================================
# ✅ Windows 환경에서 UTF-8 인코딩 보장
# ========================================================================
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

# ========================================================================
# ✅ 로깅 설정
# ========================================================================
log_file_path = os.path.join(LOG_DIR, "my_log_file.log")

def setup_logging():
    """로깅 설정 초기화 및 로거 반환"""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[logging.FileHandler(log_file_path, mode="a", encoding="utf-8")]
    )
    logging.getLogger("fdr.reader").setLevel(logging.CRITICAL)
    return logging.getLogger()

logger = setup_logging()

def flush_log():
    """로그 버퍼 플러시"""
    for handler in logger.handlers:
        handler.flush()
    logging.shutdown()

# ========================================================================
# ✅ Matplotlib 한글 폰트 설정
# ========================================================================
def set_korean_font():
    font_name = None
    if platform.system() == "Windows":
        font_path = "C:/Windows/Fonts/malgun.ttf"
        if os.path.exists(font_path):
            font_name = fm.FontProperties(fname=font_path).get_name()
    elif platform.system() == "Darwin":
        font_name = "AppleGothic"
    else:
        fonts = fm.findSystemFonts(fontext="ttf")
        for font in fonts:
            if "nanum" in os.path.basename(font).lower():
                font_name = fm.FontProperties(fname=font).get_name()
                break

    rc("font", family=font_name or "DejaVu Sans")
    plt.rcParams["axes.unicode_minus"] = False

# ========================================================================
# ✅ 환경 체크
# ========================================================================
def check_environment(required_libs):
    logger.info("=== 환경 체크 시작 ===")
    os_name = platform.system()
    os_version = platform.version()
    arch = platform.architecture()
    logger.info(f"OS: {os_name} {os_version}, Architecture: {arch}")
    logger.info(f"Python Version: {sys.version}")

    missing = []
    for lib in required_libs:
        try:
            __import__(lib)
        except ImportError:
            missing.append(lib)

    if missing:
        logger.warning(f"설치 필요: {', '.join(missing)}")
        raise ImportError(f"다음 라이브러리가 필요합니다: {', '.join(missing)}")

    logger.info("필수 라이브러리 모두 설치됨")
    flush_log()

# ========================================================================
# ✅ FDR 데이터 캐싱
# ========================================================================
def fetch_fdr_with_retry_with_cache(symbol, start=None, end=None):
    cache_key = f"{symbol}_{start}_{end}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.pkl"
    cache_path = os.path.join(DATA_DIR, cache_filename)

    if os.path.exists(cache_path):
        if (datetime.now() - datetime.fromtimestamp(os.path.getmtime(cache_path))) < timedelta(days=1):
            try:
                with open(cache_path, "rb") as f:
                    logger.info(f"캐시 데이터 로드: {symbol}")
                    return pickle.load(f)
            except Exception:
                logger.warning(f"캐시 손상 → 삭제: {cache_path}")
                os.remove(cache_path)

    try:
        logger.info(f"FinanceDataReader 데이터 가져오기 시작: {symbol}")
        df = fdr.DataReader(symbol, start=start, end=end)
        if not df.empty:
            with open(cache_path, "wb") as f:
                pickle.dump(df, f)
            logger.info(f"데이터 캐싱 완료: {symbol}")
        return df
    except (RemoteDisconnected, ssl.SSLError, Urllib3SSLError, MaxRetryError, ConnectionError, RequestsSSLError) as e:
        err = f"네트워크/SSL 오류: {e}"
        print(json.dumps({"error": err}), file=sys.stderr)
        logger.error(err)
        raise SystemExit(err)
    except Exception as e:
        err = f"일반 오류 발생: {e}"
        print(json.dumps({"error": err}), file=sys.stderr)
        logger.error(err)
        return None

# ========================================================================
# ✅ 차트 생성 캐시
# ========================================================================
def generate_chart_with_cache(symbol, stock_name, df, start_date, end_date):
    cache_key = f"{symbol}_{start_date}_{end_date}"
    cache_filename = f"{hashlib.md5(cache_key.encode()).hexdigest()}.png"
    cache_path = os.path.join(CHART_CACHE_DIR, cache_filename)

    if os.path.exists(cache_path):
        with open(cache_path, "rb") as f:
            return base64.b64encode(f.read()).decode()

    try:
        set_korean_font()
        plt.figure(figsize=(10, 5))
        plt.plot(df.index, df["Close"], label=stock_name)
        plt.title(f"{stock_name} 종가 ({start_date} ~ {end_date})")
        plt.xlabel("날짜")
        plt.ylabel("종가")
        plt.grid(True)
        plt.tight_layout()
        buf = BytesIO()
        plt.savefig(buf, format="png")
        plt.savefig(cache_path, format="png")
        plt.close()
        buf.seek(0)
        return base64.b64encode(buf.read()).decode()
    except Exception as e:
        err = f"차트 생성 오류: {e}"
        print(json.dumps({"error": err}), file=sys.stderr)
        logger.error(err)
        return None
