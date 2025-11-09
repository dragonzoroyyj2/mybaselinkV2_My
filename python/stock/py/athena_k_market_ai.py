# -*- coding: utf-8 -*-
"""
📘 athena_k_market_ai.py (v1.7.3 - JSON Serialization Fix 적용)
--------------------------------------------
✅ [CRITICAL FIX] `safe_print_json`에 사용자 정의 객체 및 NumPy 타입을 표준 파이썬 타입으로 변환하는 로직 추가.
✅ [FIX] Java 서비스와의 통신을 위해 **--years 인자 추가**.
✅ [TEMPORARY FIX] 전체 종목 분석 대신, 테스트를 위해 상위 10개 종목만 분석하도록 제한 (v1.7.1 유지)
✅ [STABLE FIX] yfinance 뉴스 로드 안정성 및 인코딩 안정화 로직 유지
"""

import os
import sys
import json
import time
import logging
import argparse
import traceback
import socket 
from pathlib import Path
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed
import io
import base64

# ==============================
# 1. 인코딩 안정화 및 초기 안전 검사
# ==============================
# (생략: 이전 코드와 동일)
try:
    if sys.platform.startswith('win'):
        sys.stdin.reconfigure(encoding='utf-8')
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

def _deep_to_serializable(data):
    """
    Recursively converts custom or non-standard objects (like frozendict, np.float64, 
    or np.bool_) into basic serializable types (dict, list, str, int, float, bool, None).
    This prevents custom JSON encoders from failing on standard types like bool.
    """
    if isinstance(data, dict) or (hasattr(data, 'items') and callable(data.items)):
        # Handles standard dicts and custom mapping types like frozendict
        return {k: _deep_to_serializable(v) for k, v in data.items()}
    elif isinstance(data, list) or isinstance(data, tuple):
        # Handles lists and tuples
        return [_deep_to_serializable(item) for item in data]
    elif isinstance(data, (np.integer, np.int64)):
        return int(data)
    elif isinstance(data, (np.floating, np.float64)):
        return float(data)
    elif isinstance(data, np.bool_):
        return bool(data)
    # Standard primitive types like str, int, float, bool, and None are returned as is.
    return data


def safe_print_json(data, status_code=1):
    """
    Prints data to standard output as a JSON string, ensuring robust serialization 
    by converting non-standard objects to native Python types first.
    """
    serializable_data = _deep_to_serializable(data)
    
    try:
        sys.stdout.write(json.dumps(serializable_data, ensure_ascii=False, indent=2) + "\n")
        sys.stdout.flush()
    except TypeError as e:
        # 이 단계에서 다시 TypeError가 발생하면, 치명적인 오류로 간주하고 출력합니다.
        error_output = {
            "error": "CRITICAL_ERROR",
            "reason": f"데이터 최종 직렬화 실패: {e}",
            "original_data_type": str(type(data)),
            "mode": "runtime_error"
        }
        sys.stdout.write(json.dumps(error_output, ensure_ascii=False, indent=2) + "\n")
        sys.stdout.flush()
    
    if status_code != 0:
        sys.exit(status_code)

def check_internet_connection(host="8.8.8.8", port=53, timeout=3):
    try:
        socket.setdefaulttimeout(timeout)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((host, port))
        s.close()
        return True
    except Exception:
        return False

if not check_internet_connection():
    safe_print_json({
        "error": "CRITICAL_ERROR",
        "reason": "인터넷 연결을 확인할 수 없습니다. 네트워크 상태를 점검해주세요. (Google DNS 연결 실패)",
        "mode": "initial_check"
    })

try:
    import FinanceDataReader as fdr
    import pandas as pd
    import mplfinance as mpf
    import matplotlib.pyplot as plt
    import numpy as np
    from scipy.signal import find_peaks
    import yfinance as yf
    import ta 
    from sklearn.preprocessing import StandardScaler
    from sklearn.cluster import KMeans
    
    DART_API_KEY = os.getenv("DART_API_KEY") 
    DART_AVAILABLE = bool(DART_API_KEY)
    if DART_AVAILABLE:
        try:
            from dart_fss import Dart
        except ImportError as e:
            DART_AVAILABLE = False
            logging.warning(f"DART_API_KEY가 설정되었으나 dart-fss 모듈이 없어 DART 기능 비활성화. ({e.name})")

except ModuleNotFoundError as e:
    safe_print_json({
        "error": "CRITICAL_ERROR",
        "reason": f"필수 모듈 누락: {e.name} 설치 필요 (pip install {e.name} scikit-learn ta)",
        "mode": "initial_check"
    })


# ==============================
# 2. 경로 및 상수 설정
# ==============================
BASE_DIR = Path(__file__).resolve().parents[2]
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data"
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json"
LOG_FILE = LOG_DIR / "stock_analyzer_ultimate.log"

MAX_RETRIES = 3         
RETRY_DELAY = 10        
CALL_DELAY = 0.3        

# ==============================
# 3. 환경 초기화 및 유틸리티
# ==============================
def setup_env():
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    
    logging.basicConfig(
        level=logging.WARNING, 
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[
            logging.FileHandler(LOG_FILE, encoding="utf-8", mode='a'),
            logging.StreamHandler(sys.stdout)
        ]
    )

def set_korean_font():
    global MPLFINANCE_FONT
    try:
        if sys.platform.startswith('win'): font_family = 'Malgun Gothic'
        elif sys.platform.startswith('darwin'): font_family = 'AppleGothic'
        else: font_family = 'NanumGothic'
        
        plt.rc('font', family=font_family)
        plt.rcParams['axes.unicode_minus'] = False
        MPLFINANCE_FONT = font_family
    except Exception: 
        MPLFINANCE_FONT = 'sans-serif'

MPLFINANCE_FONT = 'sans-serif' 
set_korean_font()
setup_env() 

def load_listing():
    if not LISTING_FILE.exists(): 
        logging.error(f"종목 리스트 파일 없음: {LISTING_FILE} -> 기본 종목(삼성전자)으로 대체합니다.")
        return [{"Code": "005930", "Name": "삼성전자", "DartCorpCode": "00126380"}] 
    try:
        with open(LISTING_FILE, "r", encoding="utf-8") as f: 
            return json.load(f)
    except Exception as e:
        logging.error(f"종목 리스트 파일 로드 실패: {e} -> 기본 종목(삼성전자)으로 대체합니다.")
        return [{"Code": "005930", "Name": "삼성전자", "DartCorpCode": "00126380"}]

def get_stock_name(symbol):
    try:
        items = load_listing()
        for item in items:
            code = item.get("Code") or item.get("code")
            if code == symbol: return item.get("Name") or item.get("name")
        return symbol
    except Exception: return symbol

def get_dart_corp_code(symbol):
    try:
        items = load_listing()
        for item in items:
            code = item.get("Code") or item.get("code")
            if code == symbol: return item.get("DartCorpCode")
        return None
    except Exception: return None

# ==============================
# 4. 고급 특징 공학 및 클러스터링 로직
# ==============================
def calculate_advanced_features(df: pd.DataFrame) -> pd.DataFrame:
    if len(df) < 200: 
        min_len = len(df)
        if min_len < 14: return df.iloc[-min_len:].copy()

        df['RSI'] = ta.momentum.RSIIndicator(close=df['Close'], window=14, fillna=False).rsi()
        df['MACD'] = ta.trend.MACD(close=df['Close'], fillna=False).macd()
        
        window_bb = 20 if min_len >= 20 else min_len
        bollinger = ta.volatility.BollingerBands(close=df['Close'], window=window_bb, window_dev=2, fillna=False)
        df['BB_High'] = bollinger.bollinger_hband_indicator()
        df['BB_Width'] = bollinger.bollinger_wband()
        
        df['SMA_20'] = ta.trend.SMAIndicator(close=df['Close'], window=20 if min_len >= 20 else min_len, fillna=False).sma_indicator()
        df['SMA_50'] = ta.trend.SMAIndicator(close=df['Close'], window=50 if min_len >= 50 else min_len, fillna=False).sma_indicator()
        df['SMA_200'] = np.nan 

        if min_len >= 20:
             df['TREND_CROSS'] = (df['SMA_20'] > df['SMA_50']).astype(int)
        else:
             df['TREND_CROSS'] = np.nan

        df = df.dropna(subset=['RSI', 'MACD', 'SMA_20'])
        return df
        
    df['RSI'] = ta.momentum.RSIIndicator(close=df['Close'], window=14, fillna=False).rsi()
    df['MACD'] = ta.trend.MACD(close=df['Close'], fillna=False).macd()
    
    bollinger = ta.volatility.BollingerBands(close=df['Close'], window=20, window_dev=2, fillna=False)
    df['BB_High'] = bollinger.bollinger_hband_indicator()
    df['BB_Width'] = bollinger.bollinger_wband()
    
    df['SMA_20'] = ta.trend.SMAIndicator(close=df['Close'], window=20, fillna=False).sma_indicator()
    df['SMA_50'] = ta.trend.SMAIndicator(close=df['Close'], window=50, fillna=False).sma_indicator()
    df['SMA_200'] = ta.trend.SMAIndicator(close=df['Close'], window=200, fillna=False).sma_indicator()
    
    df['TREND_CROSS'] = (df['SMA_20'] > df['SMA_50']).astype(int)
    
    df = df.dropna()
    return df

def add_market_regime_clustering(df: pd.DataFrame, n_clusters=4) -> pd.DataFrame:
    feature_cols = ['RSI', 'MACD', 'BB_Width', 'TREND_CROSS']
    min_data_length = 50 
    
    if len(df) < min_data_length or not all(col in df.columns for col in feature_cols):
        df['MarketRegime'] = -1 
        return df

    data = df[feature_cols].copy().dropna()
    if data.empty:
        df['MarketRegime'] = -1
        return df
        
    df['MarketRegime'] = -1 

    scaler = StandardScaler()
    scaled_data = scaler.fit_transform(data)
    
    kmeans = KMeans(n_clusters=n_clusters, random_state=42, n_init=10)
    regime = kmeans.fit_predict(scaled_data)
    
    df.loc[data.index, 'MarketRegime'] = regime
    
    return df


# ==============================
# 5. 기술적 분석 패턴 로직
# ==============================
def find_peaks_and_troughs(df, prominence=0.01, width=3):
    if len(df) < 50: return np.array([]), np.array([]) 
    
    recent_df = df.iloc[-250:].copy()
    if recent_df.empty: return np.array([]), np.array([])
    
    std_dev = recent_df['Close'].std()
    
    prominence_val = max(std_dev * prominence, recent_df['Close'].mean() * 0.005) 
    
    peaks, _ = find_peaks(recent_df['Close'], prominence=prominence_val, width=width)
    troughs, _ = find_peaks(-recent_df['Close'], prominence=prominence_val, width=width)
    
    start_idx = len(df) - len(recent_df)
    return peaks + start_idx, troughs + start_idx

def find_double_bottom(df, troughs, tolerance=0.05, min_duration=30):
    if len(df) < 50: return False, None, None, None 
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 2: return False, None, None, None
    
    idx2, idx1 = recent_troughs[-1], recent_troughs[-2] 
    if idx2 - idx1 < min_duration: return False, None, None, None 
    
    try:
        price1, price2 = df['Close'].iloc[idx1], df['Close'].iloc[idx2]
        interim_high = df['Close'].iloc[idx1:idx2].max() 
        current_price = df['Close'].iloc[-1]
    except IndexError: return False, None, None, None

    min_price_ab = min(price1, price2)
    is_price_matching = abs(price1 - price2) / min_price_ab < tolerance
    if not is_price_matching: return False, None, None, None
    if interim_high / min_price_ab < 1.1: return False, None, None, None

    is_breakout = current_price > interim_high 
    if is_breakout: return True, interim_high, 'Breakout', interim_high
    
    retrace_ratio = (current_price - min_price_ab) / (interim_high - min_price_ab) if interim_high > min_price_ab else 0
    is_potential = retrace_ratio > 0.5 and current_price < interim_high 
    
    if is_potential: return False, interim_high, 'Potential', interim_high
    return False, None, None, None

def find_triple_bottom(df, troughs, tolerance=0.05, min_duration_total=75):
    if len(df) < 75: return False, None, None, None 
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 3: return False, None, None, None
    
    idx3, idx2, idx1 = recent_troughs[-1], recent_troughs[-2], recent_troughs[-3]
    if idx3 - idx1 < min_duration_total: return False, None, None, None 

    try:
        price1, price2, price3 = df['Close'].iloc[idx1], df['Close'].iloc[idx2], df['Close'].iloc[idx3]
        high1 = df['Close'].iloc[idx1:idx2].max()
        high2 = df['Close'].iloc[idx2:idx3].max()
        current_price = df['Close'].iloc[-1]
    except IndexError: return False, None, None, None

    min_price_abc = min(price1, price2, price3)
    max_price_abc = max(price1, price2, price3)
    is_price_matching = (max_price_abc - min_price_abc) / min_price_abc < tolerance
    if not is_price_matching: return False, None, None, None
    
    neckline = max(high1, high2) 
    if neckline / min_price_abc < 1.1: return False, None, None, None
    
    is_breakout = current_price > neckline 
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    retrace_ratio = (current_price - min_price_abc) / (neckline - min_price_abc) if neckline > min_price_abc else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    
    if is_potential: return False, neckline, 'Potential', neckline
    return False, None, None, None


def find_cup_and_handle(df, peaks, troughs, handle_drop_ratio=0.3):
    if len(df) < 100: return False, None, None, None 
    recent_peaks = [p for p in peaks if p >= len(df) - 250]
    if len(recent_peaks) < 1: return False, None, None, None
    
    peak_right_idx = recent_peaks[-1]
    
    try:
        peak_right_price = df['Close'].iloc[peak_right_idx]
        current_price = df['Close'].iloc[-1]
    except IndexError: return False, None, None, None
        
    handle_start_idx = peak_right_idx 
    handle_max_drop = peak_right_price * (1 - handle_drop_ratio) 

    is_handle_forming = (df['Close'].iloc[handle_start_idx:].max() <= peak_right_price) 
    is_handle_forming &= (current_price > handle_max_drop)

    if is_handle_forming and current_price > peak_right_price:
        return True, peak_right_price, 'Breakout', peak_right_price
    
    if is_handle_forming and current_price <= peak_right_price:
        return False, peak_right_price, 'Potential', peak_right_price
        
    return False, None, None, None

# ==============================
# 6. 기본적 분석 및 악재 필터링 로직
# ==============================
def get_basic_fundamentals(code):
    fundamentals = {}
    time.sleep(CALL_DELAY) 
    try:
        yf_ticker = f"{code}.KS" if not code.endswith('.KS') else code
        ticker = yf.Ticker(yf_ticker)
        info = ticker.info
        
        if 'trailingPE' in info and info['trailingPE'] is not None:
             fundamentals['PE_Ratio'] = info['trailingPE']
        
        if 'priceToBook' in info and info['priceToBook'] is not None:
             fundamentals['PB_Ratio'] = info['priceToBook']

    except Exception as e:
        logging.warning(f"yfinance 펀더멘털 데이터 로드 실패 ({code}): {e}")
        
    return fundamentals

def get_yfinance_news(code):
    headlines = []
    yf_ticker = f"{code}.KS" if not code.endswith('.KS') else code
    
    time.sleep(CALL_DELAY) 
    try:
        ticker = yf.Ticker(yf_ticker)
        news_list = ticker.news
        
        if not news_list:
            return [] 
             
        filtered_headlines = []
        two_months_ago = datetime.now() - timedelta(days=60)
        
        for news in news_list:
            publish_timestamp = news.get('providerPublishTime')
            
            if publish_timestamp is None:
                continue

            publish_date = datetime.fromtimestamp(publish_timestamp) 
            if publish_date >= two_months_ago:
                filtered_headlines.append({"title": news.get('title'), "link": news.get('link')})
            if len(filtered_headlines) >= 3: break 
        
        return filtered_headlines 
            
    except Exception as e:
        logging.debug(f"yfinance 뉴스 로드 실패 ({code}): {e}") 
        return []

def get_fundamental_data(code):
    fundamentals = get_basic_fundamentals(code) 
    headlines = get_yfinance_news(code)
    return fundamentals, headlines

def check_for_negative_dart_disclosures(corp_code):
    if not DART_AVAILABLE or not corp_code or not DART_API_KEY: return False, None
    try:
        dart = Dart(DART_API_KEY)
        end_date = datetime.now()
        start_date = end_date - timedelta(days=60) 
        reports = dart.search(corp_code=corp_code, start_dt=start_date.strftime('%Y%m%d'))
        
        negative_keywords = ["횡령", "배임", "소송 제기", "손해배상", "거래정지", "상장폐지", "감사의견 거절", "파산", "회생"]
        for report in reports:
            if "유상증자 결정" in report.report_nm and "제3자배정" in report.report_nm: continue 
            if any(keyword in report.report_nm for keyword in negative_keywords):
                return True, f"DART 공시 악재: '{report.report_nm}'"
        return False, None
    except Exception as e:
        logging.error(f"DART 공시 확인 중 오류 ({corp_code}): {e}")
        return False, None

def check_for_negatives(fundamentals, headlines, code, corp_code):
    negative_keywords_news = ["횡령", "배임", "소송", "분쟁", "거래 정지", "악재", "하락 전망", "투자주의", "적자"]
    
    for news in headlines:
        if any(keyword in news.get('title', '') for keyword in negative_keywords_news):
            return True, f"뉴스 악재: '{news.get('title')}'"
            
    pe_ratio = fundamentals.get('PE_Ratio')
    if pe_ratio is not None and not pd.isna(pe_ratio) and pe_ratio < 0: 
        return True, f"재무 악재: P/E {pe_ratio:.1f} (적자)"

    is_negative_dart, reason_dart = check_for_negative_dart_disclosures(corp_code)
    if is_negative_dart: return True, reason_dart
        
    return False, None

# ==============================
# 7. 분석 실행 및 필터링
# ==============================

def check_ma_conditions(df, periods, analyze_patterns):
    results = {}
    
    if len(df) < 5: return results
        
    ma_cols = {20: 'SMA_20', 50: 'SMA_50', 200: 'SMA_200'}

    for p in periods:
        col_name = ma_cols.get(p)
        if col_name and col_name in df.columns and len(df) >= 1:
             results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[col_name].iloc[-1]
        elif len(df) >= p:
             df[f'ma{p}'] = df['Close'].rolling(window=p, min_periods=1).mean() 
             if len(df) >= 1:
                results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[f'ma{p}'].iloc[-1]
        else:
             results[f"above_ma{p}"] = False
    
    ma50_col = ma_cols.get(50)
    ma200_col = ma_cols.get(200)

    if ma50_col in df.columns and ma200_col in df.columns and len(df) >= 200 and len(df) >= 2:
        try:
            ma50_prev, ma50_curr = df[ma50_col].iloc[-2], df[ma50_col].iloc[-1]
            ma200_prev, ma200_curr = df[ma200_col].iloc[-2], df[ma200_col].iloc[-1]

            results["goldencross_50_200_detected"] = (ma50_prev < ma200_prev and ma50_curr > ma200_curr)
            results["deadcross_50_200_detected"] = (ma50_prev > ma200_prev and ma50_curr < ma200_curr)
        except IndexError:
             results["goldencross_50_200_detected"] = False
             results["deadcross_50_200_detected"] = False
    else:
        results["goldencross_50_200_detected"] = False
        results["deadcross_50_200_detected"] = False
    
    if analyze_patterns and len(df) >= 75: 
        peaks, troughs = find_peaks_and_troughs(df)
        _, _, db_status, db_price = find_double_bottom(df, troughs)
        _, _, tb_status, tb_price = find_triple_bottom(df, troughs)
        _, _, ch_status, ch_price = find_cup_and_handle(df, peaks, troughs)
        
        results['pattern_double_bottom_status'] = db_status
        results['db_neckline_price'] = db_price

        results['pattern_triple_bottom_status'] = tb_status
        results['tb_neckline_price'] = tb_price

        results['pattern_cup_and_handle_status'] = ch_status
        results['ch_neckline_price'] = ch_price
        
    if 'MarketRegime' in df.columns and len(df) >= 1:
        regime_value = df['MarketRegime'].iloc[-1]
        if regime_value != -1:
            results['market_regime'] = int(regime_value)

    return results

def analyze_symbol(item, periods, analyze_patterns, exclude_negatives, pattern_type_filter):
    # (생략: 이전 코드와 동일)
    code = item.get("Code") or item.get("code")
    name = item.get("Name") or item.get("name")
    corp_code = item.get("DartCorpCode")
    path = DATA_DIR / f"{code}.parquet"
    
    if not path.exists(): 
        logging.debug(f"[{code}] 데이터 파일 없음.")
        return None
    
    try:
        df_raw = pd.read_parquet(path)
        if df_raw.empty or len(df_raw) < 50: 
            logging.debug(f"[{code}] 데이터 부족 ({len(df_raw)}일).")
            return None
        
        df = df_raw.iloc[-500:].copy()

        df = calculate_advanced_features(df)
        
        if len(df) < 50: 
            logging.debug(f"[{code}] 특징 공학 후 데이터 부족 ({len(df)}일).")
            return None
        
        df = add_market_regime_clustering(df)
        
        fundamentals, headlines = get_fundamental_data(code)
        
        if exclude_negatives:
            is_negative, reason = check_for_negatives(fundamentals, headlines, code, corp_code)
            if is_negative:
                logging.info(f"[{code}] {name}: 악재성 요인으로 제외됨 - {reason}")
                return None
            
        analysis_results = check_ma_conditions(df, periods, analyze_patterns) 
        
        is_match = True
        if pattern_type_filter:
            if pattern_type_filter == 'goldencross': 
                is_match = analysis_results.get("goldencross_50_200_detected", False)
            elif pattern_type_filter in ['double_bottom', 'triple_bottom', 'cup_and_handle']: 
                status_key = f'pattern_{pattern_type_filter}_status'
                status = analysis_results.get(status_key)
                is_match = status in ['Breakout', 'Potential'] 
            elif pattern_type_filter.startswith('regime:'):
                if 'market_regime' in analysis_results:
                    try:
                        target_regime = int(pattern_type_filter.split(':')[1])
                        current_regime = analysis_results['market_regime']
                        is_match = (current_regime == target_regime)
                    except ValueError:
                        is_match = False
                else:
                    is_match = False
            elif pattern_type_filter == 'ma':
                 is_match = all(analysis_results.get(f"above_ma{p}", False) for p in periods)
            else: 
                is_match = False 
        
        if pattern_type_filter and not is_match: return None
        
        if analysis_results or fundamentals or headlines:
            # NumPy float/bool을 표준 Python float/bool로 변환하기 위해 _deep_to_serializable이 필요합니다.
            fundamentals_clean = _deep_to_serializable({k: v for k, v in fundamentals.items() if v is not None and not (isinstance(v, (float, np.float64)) and np.isnan(v))})
            analysis_clean = _deep_to_serializable({k: v for k, v in analysis_results.items() if v is not None and not (isinstance(v, (float, np.float64)) and np.isnan(v))})
            
            return {
                "ticker": code,
                "name": name,
                "technical_conditions": analysis_clean,
                "fundamentals": fundamentals_clean,
                "recent_news_headlines": headlines
            }
        return None
    except Exception as e:
        error_msg = f"[ERROR] {code} {name} 분석 실패: {e}"
        logging.error(error_msg) 
        return {
             "ticker": code,
             "name": name,
             "error": str(e),
             "traceback": traceback.format_exc(), 
             "mode": "analysis_failure"
        }

def run_analysis(workers, ma_periods_str, analyze_patterns, exclude_negatives, pattern_type_filter, years):
    """병렬 처리를 이용해 전체 종목 분석을 실행하고 진행률을 출력합니다."""
    # NOTE: 'years' 인자는 현재 데이터 로드 범위 제한에 직접 사용되지는 않지만, 
    # Java 서비스의 인자 충족을 위해 받습니다.
    start_time = time.time()
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]
    
    if pattern_type_filter and pattern_type_filter not in ['ma', 'regime'] and not pattern_type_filter.startswith('regime:'): 
        analyze_patterns = True 
    
    if 50 not in periods: periods.append(50) 
    if 200 not in periods: periods.append(200)

    items = load_listing()
    initial_item_count = len(items)
    
    # =========================================================================
    # 🌟 [v1.7.1 임시 조치] 테스트용 종목 10개로 제한
    # 전체 종목 분석을 다시 활성화하려면 아래 두 줄을 주석 처리(삭제)하면 됩니다.
    items = items[:10]
    # 🌟 =====================================================================
    
    limited_item_count = len(items) # 실제 처리할 종목 수 (10개)
    results = []
    errored_symbols = [] 
    
    logging.warning(f"분석 시작: 총 {initial_item_count} 종목 중 **{limited_item_count}개** 종목만 테스트합니다. 최대 워커 {workers}개 사용. 필터: {pattern_type_filter or 'None'}. 기간(Years): {years}")

    processed_count = 0
    
    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_item = {
            executor.submit(analyze_symbol, item, periods, analyze_patterns, exclude_negatives, pattern_type_filter): item
            for item in items
        }
        
        for future in as_completed(future_to_item):
            processed_count += 1
            # 진행률 퍼센트는 전체 종목 수(initial_item_count)를 기준으로 계산하여, 
            # 메인 애플리케이션에 부담을 주지 않도록 합니다.
            progress_percent = round((processed_count / initial_item_count) * 100, 2) 
            
            # 실시간 진행률 JSON 출력 (safe_print_json을 쓰면 exit 됨)
            sys.stdout.write(json.dumps({
                "mode": "progress",
                "total_symbols": initial_item_count,
                "processed_symbols": processed_count,
                "progress_percent": progress_percent
            }, ensure_ascii=False) + "\n")
            sys.stdout.flush()
            
            try:
                r = future.result()
                if r:
                    if r.get("mode") == "analysis_failure":
                        errored_symbols.append(r) 
                    else:
                        results.append(r)
            except Exception as e:
                code = future_to_item[future].get("Code") or future_to_item[future].get("code")
                name = future_to_item[future].get("Name") or future_to_item[future].get("name")
                error_info = {"ticker": code, "name": name, "error": str(e), "mode": "thread_exception", "traceback": traceback.format_exc()}
                errored_symbols.append(error_info)
                logging.error(f"[ERROR] {code} {name} 처리 중 스레드 예외 발생: {e}")
    
    end_time = time.time()
    
    data_check = {
        "listing_file_exists": LISTING_FILE.exists(),
        "dart_available": DART_AVAILABLE,
        "total_symbols_loaded": initial_item_count,
        "symbols_processed_ok": len(results),
        "symbols_failed": len(errored_symbols),
        "time_taken_sec": round(end_time - start_time, 2),
    }

    logging.warning(f"분석 완료: {len(results)}개 종목 필터링 됨. {len(errored_symbols)}개 종목 오류 발생. 총 소요 시간: {data_check['time_taken_sec']}초")
    
    # 최종 결과는 safe_print_json을 통해 status_code=0으로 출력
    safe_print_json({
        "results": results, 
        "errored_symbols": errored_symbols, 
        "mode": "analyze_result",
        "filter": pattern_type_filter or 'ma_only',
        "data_check": data_check
    }, status_code=0)

def generate_chart(symbol, ma_periods_str):
    # (생략: 이전 코드와 동일)
    code = symbol
    name = get_stock_name(code)
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]
    path = DATA_DIR / f"{code}.parquet"
    
    if not path.exists():
        safe_print_json({"error": f"데이터 파일을 찾을 수 없음: {path}"}, status_code=1)
        return
    
    try:
        df = pd.read_parquet(path)
        if df.empty:
            safe_print_json({"error": "데이터프레임이 비어 있습니다."}, status_code=1)
            return
            
        df_for_chart = df.iloc[-250:].copy() 
        df_for_chart = calculate_advanced_features(df_for_chart)
        
        if df_for_chart.empty:
            safe_print_json({"error": "특징 계산 후 데이터가 부족하여 차트 생성 불가."}, status_code=1)
            return

        ma_lines = []
        for p in periods:
            ma_col_name = f'SMA_{p}' if p in [20, 50, 200] else f'ma{p}'
            if ma_col_name not in df_for_chart.columns:
                df_for_chart[ma_col_name] = df_for_chart['Close'].rolling(window=p, min_periods=1).mean()

            if ma_col_name in df_for_chart.columns and not df_for_chart[ma_col_name].isnull().all():
                color_map = {5: 'red', 20: 'orange', 50: 'purple', 200: 'blue'}
                ma_lines.append(mpf.make_addplot(df_for_chart[ma_col_name], panel=0, type='line', width=1.0, 
                                                 color=color_map.get(p, 'green'), secondary_y=False))
        
        macd_plot = mpf.make_addplot(df_for_chart['MACD'], panel=2, type='line', secondary_y=False, color='red', width=1.0, title='MACD')
        
        mc = mpf.make_marketcolors(up='red', down='blue', edge='black', wick='black', volume='gray', ohlc='i') 
        
        s = mpf.make_mpf_style(marketcolors=mc, gridcolor='gray', figcolor='white', y_on_right=False, 
                               rc={'font.family': MPLFINANCE_FONT}, 
                               base_mpf_style='yahoo') 
        
        addplots = ma_lines + [macd_plot]
        
        fig, axes = mpf.plot(df_for_chart, type='candle', style=s, 
                             title=f"{name} ({code}) Technical Analysis Chart", 
                             ylabel='Price (KRW)', ylabel_lower='Volume', volume=True, 
                             addplot=addplots, figscale=1.5, returnfig=True, 
                             tight_layout=True)
        
        buf = io.BytesIO()
        fig.savefig(buf, format='png', bbox_inches='tight')
        plt.close(fig) 
        image_base64 = base64.b64encode(buf.getvalue()).decode('utf-8')
        
        safe_print_json({"ticker": code, "name": name, "chart_image_base64": image_base64, "mode": "chart"}, status_code=0)
        
    except Exception as e:
        logging.error(f"[ERROR] 차트 생성 실패 ({code} {name}): {e}\n{traceback.format_exc()}")
        safe_print_json({"error": f"차트 생성 실패: {e}"}, status_code=1)

def main():
    """스크립트의 메인 실행 함수입니다."""
    parser = argparse.ArgumentParser(description="주식 데이터 분석 및 차트 생성 스크립트")
    parser.add_argument("--mode", type=str, required=True, choices=['analyze', 'chart'], help="실행 모드 선택: 'analyze' 또는 'chart'")
    parser.add_argument("--workers", type=int, default=os.cpu_count() * 2, help="분석 모드에서 사용할 최대 스레드 수")
    parser.add_argument("--ma_periods", type=str, default="20,50,200", help="이동 평균선 기간 지정 (쉼표로 구분)")
    # --- FIX: --years 인자 추가 ---
    parser.add_argument("--years", type=int, default=5, help="분석에 사용할 과거 데이터의 연도 수")
    # -----------------------------
    parser.add_argument("--symbol", type=str, help="차트 모드에서 사용할 종목 코드")
    parser.add_argument("--analyze_patterns", action="store_true", help="패턴 감지 활성화")
    parser.add_argument("--pattern_type", type=str, 
                        choices=['ma', 'double_bottom', 'triple_bottom', 'cup_and_handle', 'goldencross', 'regime:0', 'regime:1', 'regime:2', 'regime:3'], 
                        help="필터링할 패턴 종류 (예: 'regime:0' 또는 'goldencross')") 
    parser.add_argument("--exclude_negatives", action="store_true", help="악재성 종목 제외")
    args = parser.parse_args()
    
    try:
        if args.mode == 'analyze':
            # FIX: run_analysis 함수 호출 시 args.years 인자를 전달하도록 수정
            run_analysis(args.workers, args.ma_periods, args.analyze_patterns, args.exclude_negatives, args.pattern_type, args.years) 
        elif args.mode == 'chart':
            if not args.symbol: 
                safe_print_json({
                    "error": "CRITICAL_ERROR", 
                    "reason": "차트 모드에는 --symbol 인수가 필수입니다.",
                    "mode": "argument_check"
                })
            generate_chart(args.symbol, args.ma_periods) 
    except Exception as e:
        error_msg = f"스크립트 실행 중 치명적인 오류 발생: {e}"
        safe_print_json({
            "error": "CRITICAL_ERROR", 
            "reason": error_msg,
            "traceback": traceback.format_exc(),
            "mode": "runtime_error"
        })
    sys.exit(0)

if __file__ != '<stdin>' and __name__ == "__main__":
    main()