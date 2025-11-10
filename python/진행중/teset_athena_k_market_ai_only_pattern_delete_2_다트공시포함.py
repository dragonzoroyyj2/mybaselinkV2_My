# -*- coding: utf-8 -*-
"""
📘 teset_athena_k_market_ai_only_pattern_delete.py (v1.3 - 단일 종목 필터링 최종 통합 버전)
--------------------------------------------
✅ 한국 주식 시장 데이터 분석 및 기술적 패턴 감지 스크립트
    - 기능: 종목 분석 필터링 (analyze 모드), 차트 시각화 데이터 생성 (chart 모드)
    - 특징: DART API를 통한 '단일판매·공급계약 체결' 공시 정보 통합 및 **Corp Code 매핑 로직 추가**
    - 수정: **--symbol 인자를 통한 단일 종목 분석 기능 추가**
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
import glob

import pandas as pd
import numpy as np
from scipy.signal import find_peaks
import ta
from sklearn.preprocessing import StandardScaler
from sklearn.cluster import KMeans

# ⭐ DART API 호출을 위한 requests 라이브러리
# (주의: 사용 전 'pip install requests'가 필요합니다)
try:
    import requests
except ImportError:
    requests = None
    logging.warning("requests 라이브러리를 찾을 수 없습니다. DART API 기능은 비활성화됩니다. (pip install requests 필요)")


# ==============================
# 1. 초기 안전 검사 및 필수 라이브러리 임포트
# ==============================

def safe_print_json(data, status_code=1):
    """표준 출력(stdout)으로 JSON을 안전하게 출력하고 프로세스를 종료합니다."""
    try:
        # CustomJsonEncoder를 사용하여 np 타입 및 datetime 객체 처리
        sys.stdout.write(json.dumps(data, ensure_ascii=False, indent=None, separators=(',', ':'), cls=CustomJsonEncoder) + "\n")
    except Exception as e:
        sys.stdout.write(json.dumps({"error": "JSON_SERIALIZATION_FAIL", "original_error": str(e)}, ensure_ascii=False) + "\n")
        
    sys.stdout.flush()
    if status_code != 0:
        sys.exit(status_code)

def check_internet_connection(host="8.8.8.8", port=53, timeout=3):
    """간단한 소켓 연결을 통해 인터넷 연결 상태를 확인합니다."""
    try:
        socket.setdefaulttimeout(timeout)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((host, port))
        s.close()
        return True
    except Exception:
        return False

# 스크립트 시작 시 인터넷 연결 확인
if not check_internet_connection():
    safe_print_json({"error": "CRITICAL_ERROR", "reason": "인터넷 연결을 확인할 수 없습니다.", "mode": "initial_check"})

# ==============================
# 1.5. JSON Custom Encoder 정의
# ==============================
class CustomJsonEncoder(json.JSONEncoder):
    """NumPy 타입 및 Pandas Timestamp를 표준 Python 타입으로 변환합니다."""
    def default(self, obj):
        if isinstance(obj, np.bool_):
            return bool(obj)
        if isinstance(obj, (np.integer, np.int64, np.int32)):
            return int(obj)
        if isinstance(obj, (np.floating, np.float64, np.float32)):
            if np.isnan(obj):
                return None
            return float(obj)
        if isinstance(obj, set):
            return list(obj)
        if isinstance(obj, (pd.Timestamp, datetime, np.datetime64)):
            return obj.strftime('%Y-%m-%d')
        return json.JSONEncoder.default(self, obj)


# ==============================
# 2. 경로 및 상수 설정
# ==============================
# BASE_DIR 경로 설정을 현재 스크립트 파일의 위치로 변경합니다. (Path(__file__)로 시작)
BASE_DIR = Path(__file__).resolve().parent
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data" 
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json" 
CACHE_DIR = BASE_DIR / "cache" 
LOG_FILE = LOG_DIR / "stock_analyzer_ultimate.log"

# DART API 상수 설정
DART_API_URL = "https://opendart.fss.or.kr/api/list.json"
DART_SEARCH_TERM = "단일판매·공급계약 체결" 

# DART 매핑 로직을 위한 상수/전역 변수 추가
DART_CORP_MAP_FILE = BASE_DIR / "data" / "stock_list" / "dart_corp_codes.json"
DART_CORP_CODE_MAP = {} # Ticker-CorpCode 매핑 딕셔너리


# ==============================
# 3. 환경 초기화 및 유틸리티
# ==============================

# DART Corp Code 매핑 파일을 로드하는 함수
def load_dart_corp_map():
    """dart_corp_codes.json 파일을 로드하여 전역 딕셔너리에 저장합니다."""
    global DART_CORP_CODE_MAP
    if not DART_CORP_MAP_FILE.exists():
        logging.error(f"DART 매핑 파일 없음: {DART_CORP_MAP_FILE}")
        return
    try:
        with open(DART_CORP_MAP_FILE, "r", encoding="utf-8") as f:
            DART_CORP_CODE_MAP = json.load(f)
        logging.info(f"DART Corp Code 매핑 정보 {len(DART_CORP_CODE_MAP)}개 로드 완료.")
    except Exception as e:
        logging.error(f"DART 매핑 파일 로드 실패: {e}")

def setup_env(log_level=logging.INFO):
    """환경 디렉토리를 설정하고 로깅을 초기화합니다."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    
    # DART 매핑 로딩 호출 추가
    load_dart_corp_map() 

    logging.basicConfig(
        level=log_level,
        format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
        handlers=[
            logging.FileHandler(LOG_FILE, encoding="utf-8", mode='a'),
            logging.StreamHandler(sys.stdout)
        ]
    )

def load_listing():
    """종목 리스트 파일 (stock_listing.json)을 로드합니다."""
    default_item = [{"Code": "005930", "Name": "삼성전자"}]
    if not LISTING_FILE.exists():
        logging.error(f"종목 리스트 파일 없음: {LISTING_FILE}")
        return default_item
    try:
        with open(LISTING_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        logging.error(f"종목 리스트 파일 로드 실패: {e}")
        return default_item

def get_stock_name(symbol):
    """종목 코드로 이름을 찾아 반환합니다."""
    try:
        items = load_listing()
        for item in items:
            code = item.get("Code") or item.get("code")
            if code == symbol: return item.get("Name") or item.get("name")
        return symbol
    except Exception: return symbol

# 캐시 정리 함수
def cleanup_old_cache(days=7):
    """지정된 기간(일)보다 오래된 캐시 파일을 삭제합니다."""
    logging.info(f"만료된 ({days}일 이상) 캐시 파일 정리 시작.")
    
    cutoff_time = datetime.now() - timedelta(days=days)
    
    cache_files = CACHE_DIR.glob('*.json')
    
    deleted_count = 0
    for file_path in cache_files:
        try:
            mod_time = datetime.fromtimestamp(file_path.stat().st_mtime)
            
            if mod_time < cutoff_time:
                file_path.unlink()  
                deleted_count += 1
                logging.debug(f"캐시 파일 삭제: {file_path.name}")
        except Exception as e:
            logging.error(f"캐시 파일 {file_path.name} 정리 중 오류 발생: {e}")

    logging.info(f"총 {deleted_count}개의 오래된 캐시 파일을 정리했습니다.")


# ==============================
# 4. 고급 특징 공학 및 클러스터링 로직
# ==============================

def calculate_advanced_features(df: pd.DataFrame) -> pd.DataFrame:
    """고급 패턴 인식을 위해 기술적 지표를 특징(Feature)으로 추가합니다."""
    df['RSI'] = ta.momentum.RSIIndicator(close=df['Close'], window=14, fillna=False).rsi()
    df['MACD'] = ta.trend.MACD(close=df['Close'], fillna=False).macd()
    df['MACD_Signal'] = ta.trend.MACD(close=df['Close'], fillna=False).macd_signal()
    df['MACD_Hist'] = ta.trend.MACD(close=df['Close'], fillna=False).macd_diff() 

    bollinger = ta.volatility.BollingerBands(close=df['Close'], window=20, window_dev=2, fillna=False)
    df['BB_Width'] = bollinger.bollinger_wband()

    df['SMA_20'] = ta.trend.SMAIndicator(close=df['Close'], window=20, fillna=False).sma_indicator()
    df['SMA_50'] = ta.trend.SMAIndicator(close=df['Close'], window=50, fillna=False).sma_indicator()
    df['SMA_200'] = ta.trend.SMAIndicator(close=df['Close'], window=200, fillna=False).sma_indicator()

    df['Log_Return'] = np.log(df['Close'] / df['Close'].shift(1))
    df['TREND_CROSS'] = (df['SMA_50'] > df['SMA_200']).astype(int)

    feature_subset = ['RSI', 'MACD', 'BB_Width', 'TREND_CROSS', 'SMA_200', 'Log_Return']
    df_with_features = df.copy().dropna(subset=feature_subset)
    return df_with_features

def add_market_regime_clustering(df_full: pd.DataFrame, n_clusters=4) -> pd.DataFrame:
    """K-Means 클러스터링을 통해 시장 국면(Market Regime)을 정의하고 할당합니다."""
    feature_cols = ['RSI', 'MACD', 'BB_Width', 'TREND_CROSS', 'Log_Return'] 
    min_data_length = 200

    if len(df_full) < min_data_length or not all(col in df_full.columns for col in feature_cols):
        df_full['MarketRegime'] = -1 
        return df_full

    data = df_full[feature_cols].copy()

    if data.drop_duplicates().shape[0] < n_clusters:
        df_full['MarketRegime'] = -1 
        return df_full
    
    scaler = StandardScaler()
    scaled_data = scaler.fit_transform(data) 

    try:
        kmeans = KMeans(n_clusters=n_clusters, random_state=42, n_init=10, init='k-means++')
        df_full['MarketRegime'] = kmeans.fit_predict(scaled_data) 
    except ValueError as e:
        df_full['MarketRegime'] = -1

    return df_full


# ==============================
# 5. 기술적 분석 패턴 로직
# ==============================

def find_peaks_and_troughs(df, prominence_ratio=0.005, width=3):
    """주요 봉우리(Peaks)와 골짜기(Troughs) 인덱스를 찾습니다 (최근 250일 기준)."""
    recent_df = df.iloc[-250:].copy()
    if recent_df.empty: return np.array([]), np.array([])
    # Note: Use a fixed window for std to prevent instability if data changes often
    std_dev = recent_df['Close'].std() 
    prominence_val = std_dev * prominence_ratio 
    
    peaks, _ = find_peaks(recent_df['Close'], prominence=prominence_val, width=width)
    troughs, _ = find_peaks(-recent_df['Close'], prominence=prominence_val, width=width)
    
    start_idx = len(df) - len(recent_df)
    return peaks + start_idx, troughs + start_idx

def find_double_bottom(df, troughs, tolerance=0.05, min_duration=30, min_retrace=0.1):
    """이중 바닥 (Double Bottom) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 2: return False, None, None, None
    
    idx2, idx1 = recent_troughs[-1], recent_troughs[-2]
    price1, price2 = df['Close'].iloc[idx1], df['Close'].iloc[idx2]
    
    if idx2 - idx1 < min_duration: return False, None, None, None 
    
    min_price = min(price1, price2)
    max_price = max(price1, price2)
    is_price_matching = (max_price - min_price) / min_price < tolerance
    if not is_price_matching: return False, None, None, None
    
    interim_high = df['Close'].iloc[idx1:idx2].max()
    neckline = interim_high
    
    retrace_from_bottom = neckline - min_price
    if retrace_from_bottom / min_price < min_retrace: return False, None, None, None 
    
    current_price = df['Close'].iloc[-1]
    
    is_breakout = current_price > neckline 
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    retrace_ratio = (current_price - min_price) / (neckline - min_price) if neckline > min_price else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    if is_potential: return False, neckline, 'Potential', neckline
    
    return False, neckline, 'None', neckline 

def find_triple_bottom(df, troughs, tolerance=0.05, min_duration_total=75, min_retrace=0.1):
    """삼중 바닥 (Triple Bottom) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 3: return False, None, None, None
    
    idx3, idx2, idx1 = recent_troughs[-1], recent_troughs[-2], recent_troughs[-3]
    price1, price2, price3 = df['Close'].iloc[idx1], df['Close'].iloc[idx2], df['Close'].iloc[idx3]
    
    if idx3 - idx1 < min_duration_total: return False, None, None, None
    
    min_price = min(price1, price2, price3)
    max_price = max(price1, price2, price3)
    is_price_matching = (max_price - min_price) / min_price < tolerance
    if not is_price_matching: return False, None, None, None
    
    high1 = df['Close'].iloc[idx1:idx2].max()
    high2 = df['Close'].iloc[idx2:idx3].max()
    neckline = max(high1, high2)
    
    retrace_from_bottom = neckline - min_price
    if retrace_from_bottom / min_price < min_retrace: return False, None, None, None
    
    current_price = df['Close'].iloc[-1]
    
    is_breakout = current_price > neckline
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    retrace_ratio = (current_price - min_price) / (neckline - min_price) if neckline > min_price else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    if is_potential: return False, neckline, 'Potential', neckline
    
    return False, neckline, 'None', neckline

def find_cup_and_handle(df, peaks, troughs, handle_drop_ratio=0.3):
    """컵 앤 핸들 (Cup and Handle) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_peaks = [p for p in peaks if p >= len(df) - 250]
    if len(recent_peaks) < 2: return False, None, None, None
    
    peak_right_idx = recent_peaks[-1]
    peak_right_price = df['Close'].iloc[peak_right_idx]
    
    handle_start_idx = peak_right_idx
    handle_max_drop = peak_right_price * (1 - handle_drop_ratio) 
    current_price = df['Close'].iloc[-1]
    neckline = peak_right_price 
    
    is_handle_forming = (df['Close'].iloc[handle_start_idx:].max() <= peak_right_price) 
    is_handle_forming &= (current_price > handle_max_drop) 
    
    if is_handle_forming and current_price > neckline:
        return True, neckline, 'Breakout', neckline 
    if is_handle_forming and current_price <= neckline:
        return False, neckline, 'Potential', neckline 
        
    return False, neckline, 'None', neckline 


# ==============================
# 6. 기술적 조건 및 패턴 분석
# ==============================

def check_ma_conditions(df, periods, analyze_patterns):
    """이동 평균선 조건 및 패턴 분석을 수행하고 결과를 딕셔너리로 반환합니다."""
    results = {}
    ma_cols = {20: 'SMA_20', 50: 'SMA_50', 200: 'SMA_200'}

    if len(df) < 200: analyze_patterns = False

    # 1. 주가와 MA 비교
    for p in periods:
        col_name = ma_cols.get(p)
        if col_name and col_name in df.columns and not df.empty:
            results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[col_name].iloc[-1]
        else:
            results[f"above_ma{p}"] = False

    # 2. 골든/데드 크로스 감지 (50일선 vs 200일선)
    ma50_col = ma_cols.get(50)
    ma200_col = ma_cols.get(200)

    if ma50_col in df.columns and ma200_col in df.columns and len(df) >= 200:
        ma50_prev, ma50_curr = df[ma50_col].iloc[-2], df[ma50_col].iloc[-1]
        ma200_prev, ma200_curr = df[ma200_col].iloc[-2], df[ma200_col].iloc[-1]

        results["goldencross_50_200_detected"] = (ma50_prev < ma200_prev and ma50_curr > ma200_curr)
        results["deadcross_50_200_detected"] = (ma50_prev > ma200_prev and ma50_curr < ma200_curr)
    else:
        results["goldencross_50_200_detected"] = False
        results["deadcross_50_200_detected"] = False

    # 3. 기술적 패턴 분석 
    if analyze_patterns:
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

    # 4. 시장 국면 (Market Regime)
    if 'MarketRegime' in df.columns and not df.empty:
        results['market_regime'] = int(df['MarketRegime'].iloc[-1])
    else:
        results['market_regime'] = -1

    return results


# ==============================
# 6.5. DART API 공시 조회 로직
# ==============================

def get_dart_corp_code(symbol):
    """
    종목 코드를 DART 공시 시스템에서 사용하는 기업 코드(corp_code)로 변환합니다.
    """
    # 전역 딕셔너리에서 Corp Code를 찾아 반환합니다. 
    global DART_CORP_CODE_MAP
    return DART_CORP_CODE_MAP.get(symbol, "")


def fetch_dart_contracts(symbol: str, api_key: str) -> list:
    """
    DART API를 호출하여 '단일판매·공급계약 체결' 공시를 조회합니다.
    최근 90일 이내 공시만 필터링합니다.
    """
    if not requests or not api_key:
        return []
        
    end_date = datetime.now().strftime("%Y%m%d")
    start_date = (datetime.now() - timedelta(days=90)).strftime("%Y%m%d")
    
    # 수정된 get_dart_corp_code 함수 사용
    corp_code = get_dart_corp_code(symbol)
    
    # Corp Code가 유효하지 않으면 요청을 건너뜁니다.
    if not corp_code:
        logging.debug(f"[{symbol}] DART Corp Code를 찾을 수 없어 공시 조회를 건너뜁니다.")
        return []

    params = {
        'crtfc_key': api_key,
        'corp_code': corp_code,
        'bgn_de': start_date,
        'end_de': end_date,
        'pblntf_ty': 'A',
        'mrkt_se': 'A',
        'page_no': '1',
        'page_count': '100'
    }
    
    try:
        response = requests.get(DART_API_URL, params=params, timeout=5)
        response.raise_for_status()
        data = response.json()
        
        if data.get('status') != '000':
            # '020'은 조회된 데이터가 없다는 의미로, 오류가 아닙니다.
            if data.get('status') != '020': 
                 logging.warning(f"DART API 오류 ({symbol} / {corp_code}): {data.get('message', '알 수 없는 오류')}")
            return []
            
        reports = data.get('list', [])
        
        contract_reports = [
            {
                'title': r['report_nm'],
                'date': r['rcept_dt'],
                'link': f"http://dart.fss.or.kr/dsaf001/main.do?rcpNo={r['rcept_no']}"
            }
            for r in reports if DART_SEARCH_TERM in r['report_nm']
        ]
        
        return contract_reports
        
    except requests.exceptions.RequestException as e:
        logging.error(f"DART API 요청 실패 ({symbol}): {e}")
        return []
    except Exception as e:
        logging.error(f"DART 데이터 처리 중 오류 ({symbol}): {e}")
        return []


# ==============================
# 7. 분석 실행 및 캐싱 로직
# ==============================

def analyze_symbol(item, periods, analyze_patterns, pattern_type_filter, dart_api_key): 
    """단일 종목을 분석하고 필터링 조건에 맞는지 확인하여 결과를 반환합니다."""
    code = item.get("Code") or item.get("code")
    name = item.get("Name") or item.get("name")
    path = DATA_DIR / f"{code}.parquet"

    if not path.exists():
        logging.debug(f"[{code}] 데이터 파일 없음.")
        return None

    try:
        df_raw = pd.read_parquet(path)
        if df_raw.index.dtype != 'datetime64[ns]' and 'Date' in df_raw.columns:
            df_raw = df_raw.set_index('Date')
            
        if df_raw.empty or len(df_raw) < 250:
            logging.debug(f"[{code}] 데이터 부족 ({len(df_raw)}일).")
            return None

        df_full = calculate_advanced_features(df_raw)
        df_full = add_market_regime_clustering(df_full)
        
        df_analyze = df_full.iloc[-250:].copy() 

        if len(df_analyze) < 200: 
            logging.debug(f"[{code}] 최종 분석 데이터 부족 ({len(df_analyze)}일).")
            return None

        analysis_results = check_ma_conditions(df_analyze, periods, analyze_patterns)

        # 필터링 로직 적용
        is_match = True
        if pattern_type_filter:
            if pattern_type_filter == 'goldencross':
                is_match = analysis_results.get("goldencross_50_200_detected", False)
            elif pattern_type_filter == 'deadcross': 
                is_match = analysis_results.get("deadcross_50_200_detected", False)
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
                is_match = all(analysis_results.get(f"above_ma{p}", False) for p in periods if p in [20, 50, 200]) # 20, 50, 200만 확인
            elif pattern_type_filter == 'all_below_ma':
                is_match = all(
                    (df_analyze['Close'].iloc[-1] < df_analyze.get(f'SMA_{p}', df_analyze.get(f'ma{p}', 0)).iloc[-1])
                    for p in periods if p in [20, 50, 200]
                )
            else:
                is_match = False

        # DART 공시 정보 조회 및 통합
        dart_contracts = []
        if dart_api_key:
            dart_contracts = fetch_dart_contracts(code, dart_api_key)


        if pattern_type_filter and not is_match: 
            logging.debug(f"[{code}] 필터 '{pattern_type_filter}' 불일치.")
            return None

        if analysis_results:
            analysis_clean = {k: v for k, v in analysis_results.items() if v is not None}
            sort_score = analysis_clean.get('market_regime', -1) 
            
            return {
                "ticker": code,
                "name": name,
                "technical_conditions": analysis_clean, 
                "dart_contracts": dart_contracts, 
                "sort_score": sort_score 
            }
        return None
    except Exception as e:
        logging.error(f"[ERROR] {code} {name} 분석 실패: {e}\n{traceback.format_exc()}")
        return None

# ⭐⭐⭐ run_analysis 함수 헤더 및 본문 수정 (단일 종목 필터링 로직) ⭐⭐⭐
def run_analysis(workers, ma_periods_str, analyze_patterns, pattern_type_filter, top_n, dart_api_key, symbol_filter=None): 
    """병렬 처리를 이용해 전체 종목 분석을 실행하고, 일일 캐싱을 적용합니다."""
    
    cleanup_old_cache() 
    
    start_time = time.time()
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]

    today_str = datetime.now().strftime("%Y%m%d")
    cache_filter_key = f"{pattern_type_filter or 'ma_only'}_{'dart' if dart_api_key else 'no_dart'}" 
    cache_key = f"{today_str}_{cache_filter_key.replace(':', '_')}_{top_n}.json" 
    cache_path = CACHE_DIR / cache_key
    
    # 캐시 확인 및 로드 (단일 종목 분석 시에는 캐시 로드를 건너뛰는 것이 좋지만, 기존 로직 유지)
    if not symbol_filter and cache_path.exists(): # 단일 종목 분석이 아닐 때만 캐시 로드 시도
        try:
            with open(cache_path, 'r', encoding='utf-8') as f:
                cached_data = json.load(f)
            logging.info(f"캐시 로드 성공: {cache_key}")
            sys.stdout.write(json.dumps(cached_data, ensure_ascii=False, indent=None, separators=(',', ':'), cls=CustomJsonEncoder) + "\n")
            sys.stdout.flush()
            sys.exit(0)
        except Exception as e:
            logging.error(f"캐시 파일 로드/파싱 실패: {e}. 재분석을 시도합니다.")

    # 분석 실행 준비
    if pattern_type_filter and pattern_type_filter not in ['ma', 'all_below_ma'] and not pattern_type_filter.startswith('regime:'):
        analyze_patterns = True

    if 50 not in periods: periods.append(50)
    if 200 not in periods: periods.append(200)

    items = load_listing()
    
    # ⭐⭐⭐ 핵심 수정 부분: 단일 종목 필터링 로직 ⭐⭐⭐
    if symbol_filter:
        items = [item for item in items if (item.get("Code") or item.get("code")) == symbol_filter]
        if not items:
            logging.error(f"지정된 종목 코드({symbol_filter})를 리스팅에서 찾을 수 없습니다.")
            safe_print_json({"error": "SYMBOL_NOT_FOUND", "ticker": symbol_filter}, status_code=1)
            return
    # ⭐⭐⭐ 수정 부분 끝 ⭐⭐⭐
    
    initial_item_count = len(items) # 필터링 후 종목 수
    total_symbols_loaded = len(load_listing()) # 원래 로드된 전체 종목 수 (진행률 분모로 사용)
    
    if initial_item_count == 0:
        safe_print_json({"error": "LISTING_DATA_EMPTY" if not symbol_filter else "SYMBOL_NOT_FOUND"}, status_code=1)
        return

    results = []
    logging.info(f"분석 시작 (캐시 미스): 총 {initial_item_count} 종목, 필터: {pattern_type_filter or 'None'}")
    processed_count = 0

    # 스레드 풀을 이용한 병렬 분석
    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_item = {
            executor.submit(analyze_symbol, item, periods, analyze_patterns, pattern_type_filter, dart_api_key): item
            for item in items
        }

        for future in as_completed(future_to_item):
            processed_count += 1
            
            # 단일 종목이든 전체 종목이든, 현재 처리된 종목 수를 기준으로 진행률 계산
            progress_percent = round((processed_count / initial_item_count) * 100, 2) 

            # 진행 상황 JSON 출력
            sys.stdout.write(json.dumps({
                "mode": "progress",
                "total_symbols": initial_item_count, # 필터링된 종목 수 (1개 또는 전체)
                "processed_symbols": processed_count,
                "progress_percent": progress_percent
            }, ensure_ascii=False, cls=CustomJsonEncoder, indent=None, separators=(',', ':')) + "\n")
            sys.stdout.flush()

            try:
                r = future.result()
                if r: results.append(r)
            except Exception as e:
                code = future_to_item[future].get("Code") or future_to_item[future].get("code")
                name = future_to_item[future].get("Name") or future_to_item[future].get("name")
                logging.error(f"[ERROR] {code} {name} 처리 중 예외 발생: {e}")

    # 결과 정렬 및 상위 N개 선택
    results.sort(key=lambda x: x.get('sort_score', -1), reverse=True)
    final_results = results[:top_n] if top_n > 0 else results
    
    for r in final_results:
        r.pop('sort_score', None)

    end_time = time.time()

    data_check = {
        "listing_file_exists": LISTING_FILE.exists(),
        "total_symbols_loaded": total_symbols_loaded, # 원래 로드된 전체 종목 수
        "symbols_processed": initial_item_count, # 처리된 종목 수 (필터링 적용 후)
        "symbols_filtered": len(results),
        "symbols_returned": len(final_results),
        "time_taken_sec": round(end_time - start_time, 2),
    }

    # 캐시 저장 (단일 종목 분석이 아닐 때만 저장)
    final_output = {
        "results": final_results,
        "mode": "analyze_result",
        "filter": pattern_type_filter or 'ma_only',
        "data_check": data_check
    }
    
    if not symbol_filter:
        try:
            with open(cache_path, 'w', encoding='utf-8') as f:
                json.dump(final_output, f, ensure_ascii=False, cls=CustomJsonEncoder, indent=None, separators=(',', ':'))
            logging.info(f"분석 결과 캐시 저장 완료: {cache_key}")
        except Exception as e:
            logging.error(f"캐시 파일 저장 실패: {e}")

    # 최종 결과 출력
    logging.info(f"분석 완료 및 결과 반환. 총 소요 시간: {data_check['time_taken_sec']}초")
    safe_print_json(final_output, status_code=0)
# ⭐⭐⭐ run_analysis 함수 수정 끝 ⭐⭐⭐


# ==============================
# 8. 차트 생성 로직
# ==============================

def generate_chart(symbol, ma_periods_str, chart_period):
    """
    단일 종목의 시계열 데이터를 Chart.js JSON 포맷으로 변환하여 반환합니다.
    (크로스 지점 및 패턴 넥라인 정보 포함)
    """
    code = symbol
    name = get_stock_name(code)
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()] 
    path = DATA_DIR / f"{code}.parquet"

    if not path.exists():
        safe_print_json({"error": f"데이터 파일을 찾을 수 없음: {path}"}, status_code=1)
        return

    try:
        df = pd.read_parquet(path)
        
        if df.index.dtype != 'datetime64[ns]' and 'Date' in df.columns:
            df = df.set_index('Date')
            
        if df.empty:
            safe_print_json({"error": "데이터프레임이 비어 있습니다."}, status_code=1)
            return

        df_full = calculate_advanced_features(df)
        df_for_chart = df_full.iloc[-chart_period:].copy()

        if df_for_chart.empty:
            safe_print_json({"error": "특징 계산 후 데이터가 부족하여 차트 생성 불가."}, status_code=1)
            return

        # 1. 캔들스틱 데이터 포맷팅 (OHLCV)
        ohlcv_data = []
        for index, row in df_for_chart.iterrows():
            ohlcv_data.append({
                "x": index.strftime('%Y-%m-%d'), 
                "o": row['Open'], "h": row['High'], "l": row['Low'], "c": row['Close'], "v": row['Volume']
            })

        # 2. 이동평균선(MA) 데이터 포맷팅
        ma_data = {}
        for p in periods:
            ma_col_name = f'SMA_{p}'
            if ma_col_name not in df_for_chart.columns:
                 # 없는 MA를 다시 계산 (Parquet에 저장되지 않은 경우 대비)
                 df_for_chart[ma_col_name] = df_for_chart['Close'].rolling(window=p, min_periods=1).mean() 

            ma_values = []
            for index, row in df_for_chart.iterrows():
                if not pd.isna(row[ma_col_name]):
                    ma_values.append({"x": index.strftime('%Y-%m-%d'), "y": row[ma_col_name]})
            ma_data[f"MA{p}"] = ma_values
        
        # 3. MACD 데이터 포맷팅
        macd_data = {"MACD": [], "Signal": [], "Histogram": []}
        for index, row in df_for_chart.iterrows():
            date_str = index.strftime('%Y-%m-%d')
            if not pd.isna(row['MACD']):
                macd_data["MACD"].append({"x": date_str, "y": row['MACD']})
            if not pd.isna(row['MACD_Signal']):
                macd_data["Signal"].append({"x": date_str, "y": row['MACD_Signal']})
            if not pd.isna(row['MACD_Hist']):
                macd_data["Histogram"].append({"x": date_str, "y": row['MACD_Hist']})

        # 4. 크로스 지점 감지 및 패턴 넥라인 정보 추가
        cross_data = []
        pattern_data = []
        
        ma50_col = 'SMA_50'
        ma200_col = 'SMA_200'
        
        # 4-1. MA 크로스 지점 감지
        if ma50_col in df_for_chart.columns and ma200_col in df_for_chart.columns:
            ma_cross = df_for_chart[ma50_col] > df_for_chart[ma200_col]
            cross_points = ma_cross[ma_cross != ma_cross.shift(1)]

            for date, is_above in cross_points.items():
                if date == df_for_chart.index[0]: continue
                prev_above = ma_cross.shift(1).loc[date]
                cross_type = ""
                
                if not prev_above and is_above: cross_type = "GoldenCross"
                elif prev_above and not is_above: cross_type = "DeadCross"
                
                if cross_type:
                    cross_data.append({"x": date.strftime('%Y-%m-%d'), "y": df_for_chart.loc[date, 'Close'], "type": cross_type})

        # 4-2. 패턴 넥라인 정보 감지
        peaks_all, troughs_all = find_peaks_and_troughs(df_full)
        
        _, db_neckline, db_status, _ = find_double_bottom(df_full, troughs_all)
        _, tb_neckline, tb_status, _ = find_triple_bottom(df_full, troughs_all)
        _, ch_neckline, ch_status, _ = find_cup_and_handle(df_full, peaks_all, troughs_all)

        today_date = df_full.index[-1].strftime('%Y-%m-%d')
        chart_min_close = df_for_chart['Close'].min()
        chart_max_close = df_for_chart['Close'].max()

        patterns_to_check = [
            ("DoubleBottom", db_neckline, db_status),
            ("TripleBottom", tb_neckline, tb_status),
            ("CupAndHandle", ch_neckline, ch_status)
        ]

        for p_name, p_neckline, p_status in patterns_to_check:
            # 차트 범위 내에 넥라인이 있을 때만 표시
            if p_neckline and (chart_min_close * 0.95 < p_neckline < chart_max_close * 1.05):
                pattern_data.append({"x": today_date, "y": p_neckline, "type": p_name, "status": p_status})


        # 5. 최종 결과 JSON 구성
        final_output = {
            "ticker": code,
            "name": name,
            "mode": "chart_data",
            "ohlcv_data": ohlcv_data,
            "ma_data": ma_data,
            "macd_data": macd_data,
            "cross_points": cross_data,
            "pattern_points": pattern_data
        }

        safe_print_json(final_output, status_code=0)

    except Exception as e:
        logging.error(f"[ERROR] Chart.js 데이터 생성 실패 ({code} {name}): {e}\n{traceback.format_exc()}")
        safe_print_json({"error": f"Chart.js 데이터 생성 실패: {e}"}, status_code=1)


# ⭐⭐⭐ main 함수 수정 (인자 전달 로직) ⭐⭐⭐
def main():
    """스크립트의 메인 실행 함수입니다. 인수를 파싱하고 모드별 함수를 호출합니다."""
    parser = argparse.ArgumentParser(description="주식 데이터 분석 및 차트 데이터 생성 스크립트")
    parser.add_argument("--mode", type=str, required=True, choices=['analyze', 'chart'], help="실행 모드 선택: 'analyze' 또는 'chart'")
    parser.add_argument("--workers", type=int, default=os.cpu_count() * 2, help="분석 모드에서 사용할 최대 스레드 수")
    parser.add_argument("--ma_periods", type=str, default="20,50,200", help="이동 평균선 기간 지정 (쉼표로 구분, 예: 5,20,50)")
    parser.add_argument("--chart_period", type=int, default=250, help="차트 모드에서 표시할 거래일 수 (기본값: 250일)")
    
    # --symbol 인자는 analyze와 chart 모두에서 사용될 수 있으므로, 따로 정의합니다.
    parser.add_argument("--symbol", type=str, help="분석 또는 차트 모드에서 사용할 단일 종목 코드 (Ticker)") 
    
    parser.add_argument("--analyze_patterns", action="store_true", help="패턴 감지 활성화")
    parser.add_argument("--pattern_type", type=str,
                         choices=['ma', 'all_below_ma', 'double_bottom', 'triple_bottom', 'cup_and_handle', 'goldencross', 'deadcross', 'regime:0', 'regime:1', 'regime:2', 'regime:3'],
                         help="분석 모드에서 필터링할 패턴 종류 (예: goldencross, regime:0)")
    parser.add_argument("--debug", action="store_true", help="디버그 모드 활성화 (로깅 레벨 DEBUG)")
    parser.add_argument("--top_n", type=int, default=10, help="분석 결과 중 상위 N개 종목만 반환 (0 이하: 전체 반환)")
    parser.add_argument("--dart_api_key", type=str, default="", help="DART API 서비스 키 (분석 모드에서 공시 정보 통합 시 사용)")

    args = parser.parse_args()
    
    log_level = logging.DEBUG if args.debug else logging.INFO
    setup_env(log_level)

    try:
        if args.mode == 'analyze':
            run_analysis(
                args.workers, 
                args.ma_periods, 
                args.analyze_patterns, 
                args.pattern_type, 
                args.top_n, 
                args.dart_api_key,
                symbol_filter=args.symbol # ⭐⭐⭐ run_analysis 함수로 인자 전달 ⭐⭐⭐
            )
        elif args.mode == 'chart':
            if not args.symbol:
                safe_print_json({"error": "Chart 모드는 --symbol 인수가 필요합니다."}, status_code=1)
                return
            generate_chart(args.symbol, args.ma_periods, args.chart_period)
            
    except Exception as e:
        logging.critical(f"스크립트 메인 실행 중 치명적인 오류 발생: {e}\n{traceback.format_exc()}")
        safe_print_json({"error": "CRITICAL_RUNTIME_ERROR", "details": str(e)}, status_code=1)

if __name__ == "__main__":
    main()
# ⭐⭐⭐ main 함수 수정 끝 ⭐⭐⭐