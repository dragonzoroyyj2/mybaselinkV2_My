# -*- coding: utf-8 -*-
"""
📘 stock_analyzer_ultimate.py (v0.9 - 주석 상세 버전)
--------------------------------------------
✅ 한국 주식 시장 데이터 분석 및 기술적 패턴 감지 스크립트
   - 기능: 종목 분석 필터링 (analyze 모드), 차트 시각화 데이터 생성 (chart 모드)
   - 특징: MA 크로스 지점, 패턴 넥라인 정보 차트 데이터에 포함됨
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
import glob 
import pandas as pd
import numpy as np
from scipy.signal import find_peaks
import ta
from sklearn.preprocessing import StandardScaler
from sklearn.cluster import KMeans

# ==============================
# 1. 초기 안전 검사 및 필수 라이브러리 임포트
# ==============================

def safe_print_json(data, status_code=1):
    """표준 출력(stdout)으로 JSON을 안전하게 출력하고 프로세스를 종료합니다."""
    try:
        # JSON 직렬화 및 출력 (ensure_ascii=False로 한글 깨짐 방지)
        sys.stdout.write(json.dumps(data, ensure_ascii=False, indent=None, separators=(',', ':'), cls=CustomJsonEncoder) + "\n")
    except Exception as e:
        # JSON 직렬화 실패 시 오류 메시지 출력
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
        # NumPy 타입 변환
        if isinstance(obj, np.bool_):
            return bool(obj)
        if isinstance(obj, (np.integer, np.int64, np.int32)):
            return int(obj)
        if isinstance(obj, (np.floating, np.float64, np.float32)):
            if np.isnan(obj): # NaN 값은 JSON에서 null이 되도록 None으로 처리
                return None
            return float(obj)
        if isinstance(obj, set):
            return list(obj)
        # 날짜/시간 타입 변환
        if isinstance(obj, (pd.Timestamp, datetime, np.datetime64)):
            return obj.strftime('%Y-%m-%d')
        return json.JSONEncoder.default(self, obj)


# ==============================
# 2. 경로 및 상수 설정
# ==============================
BASE_DIR = Path(".").resolve()
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data" # 종목 데이터(.parquet)가 저장된 위치
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json" # 종목 코드 리스트 파일
CACHE_DIR = BASE_DIR / "cache" 
LOG_FILE = LOG_DIR / "stock_analyzer_ultimate.log"

# ==============================
# 3. 환경 초기화 및 유틸리티
# ==============================
def setup_env(log_level=logging.INFO):
    """환경 디렉토리를 설정하고 로깅을 초기화합니다."""
    # 필수 디렉토리 생성
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    # 로깅 설정
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

# ==============================
# 4. 고급 특징 공학 및 클러스터링 로직
# ==============================

def calculate_advanced_features(df: pd.DataFrame) -> pd.DataFrame:
    """고급 패턴 인식을 위해 기술적 지표를 특징(Feature)으로 추가합니다."""
    # 모멘텀 지표
    df['RSI'] = ta.momentum.RSIIndicator(close=df['Close'], window=14, fillna=False).rsi()
    df['MACD'] = ta.trend.MACD(close=df['Close'], fillna=False).macd()
    df['MACD_Signal'] = ta.trend.MACD(close=df['Close'], fillna=False).macd_signal()
    df['MACD_Hist'] = ta.trend.MACD(close=df['Close'], fillna=False).macd_diff() 

    # 볼린저 밴드 너비 (변동성 지표)
    bollinger = ta.volatility.BollingerBands(close=df['Close'], window=20, window_dev=2, fillna=False)
    df['BB_Width'] = bollinger.bollinger_wband()

    # 이동평균선 (MA) - 20, 50, 200일선
    df['SMA_20'] = ta.trend.SMAIndicator(close=df['Close'], window=20, fillna=False).sma_indicator()
    df['SMA_50'] = ta.trend.SMAIndicator(close=df['Close'], window=50, fillna=False).sma_indicator()
    df['SMA_200'] = ta.trend.SMAIndicator(close=df['Close'], window=200, fillna=False).sma_indicator()

    # 로그 수익률
    df['Log_Return'] = np.log(df['Close'] / df['Close'].shift(1))

    # 50일선이 200일선 위에 있는지 (추세 플래그)
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
    scaled_data = scaler.fit_transform(data) # 데이터 정규화

    try:
        # K-Means 클러스터링 실행 (4개의 국면으로 분류)
        kmeans = KMeans(n_clusters=n_clusters, random_state=42, n_init=10, init='k-means++')
        df_full['MarketRegime'] = kmeans.fit_predict(scaled_data) # 국면 번호 (0, 1, 2, 3) 할당
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
    std_dev = recent_df['Close'].std()
    prominence_val = std_dev * prominence_ratio # 봉우리/골짜기 판단 기준 높이
    
    # find_peaks는 양수 값에서 봉우리를 찾으므로, 골짜기는 가격에 -1을 곱하여 찾음
    peaks, _ = find_peaks(recent_df['Close'], prominence=prominence_val, width=width)
    troughs, _ = find_peaks(-recent_df['Close'], prominence=prominence_val, width=width)
    
    start_idx = len(df) - len(recent_df)
    # 인덱스를 전체 데이터프레임 기준으로 변환하여 반환
    return peaks + start_idx, troughs + start_idx

def find_double_bottom(df, troughs, tolerance=0.05, min_duration=30, min_retrace=0.1):
    """이중 바닥 (Double Bottom) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 2: return False, None, None, None
    
    # 최근 두 개의 골짜기 인덱스
    idx2, idx1 = recent_troughs[-1], recent_troughs[-2]
    price1, price2 = df['Close'].iloc[idx1], df['Close'].iloc[idx2]
    
    if idx2 - idx1 < min_duration: return False, None, None, None # 최소 기간 확인
    
    # 가격 일치성 확인 (tolerance 범위 내)
    min_price = min(price1, price2)
    max_price = max(price1, price2)
    is_price_matching = (max_price - min_price) / min_price < tolerance
    if not is_price_matching: return False, None, None, None
    
    # 넥라인 (두 골짜기 사이의 최고점)
    interim_high = df['Close'].iloc[idx1:idx2].max()
    neckline = interim_high
    
    # 최소 반등 (retrace) 확인
    retrace_from_bottom = neckline - min_price
    if retrace_from_bottom / min_price < min_retrace: return False, None, None, None 
    
    current_price = df['Close'].iloc[-1]
    
    # 패턴 상태 판단
    is_breakout = current_price > neckline # 돌파
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    # 잠재적 형성 (넥라인 돌파 전, 어느 정도 반등했을 때)
    retrace_ratio = (current_price - min_price) / (neckline - min_price) if neckline > min_price else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    if is_potential: return False, neckline, 'Potential', neckline
    
    return False, neckline, 'None', neckline # 패턴 불일치 시에도 넥라인 가격은 반환 (차트 시각화 용도)

def find_triple_bottom(df, troughs, tolerance=0.05, min_duration_total=75, min_retrace=0.1):
    """삼중 바닥 (Triple Bottom) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 3: return False, None, None, None
    
    # 최근 세 개의 골짜기 인덱스
    idx3, idx2, idx1 = recent_troughs[-1], recent_troughs[-2], recent_troughs[-3]
    price1, price2, price3 = df['Close'].iloc[idx1], df['Close'].iloc[idx2], df['Close'].iloc[idx3]
    
    if idx3 - idx1 < min_duration_total: return False, None, None, None
    
    # 가격 일치성 확인
    min_price = min(price1, price2, price3)
    max_price = max(price1, price2, price3)
    is_price_matching = (max_price - min_price) / min_price < tolerance
    if not is_price_matching: return False, None, None, None
    
    # 넥라인 (세 골짜기 사이의 최고점 중 최대값)
    high1 = df['Close'].iloc[idx1:idx2].max()
    high2 = df['Close'].iloc[idx2:idx3].max()
    neckline = max(high1, high2)
    
    # 최소 반등 확인
    retrace_from_bottom = neckline - min_price
    if retrace_from_bottom / min_price < min_retrace: return False, None, None, None
    
    current_price = df['Close'].iloc[-1]
    
    # 패턴 상태 판단 (돌파/잠재적 형성)
    is_breakout = current_price > neckline
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    retrace_ratio = (current_price - min_price) / (neckline - min_price) if neckline > min_price else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    if is_potential: return False, neckline, 'Potential', neckline
    
    return False, neckline, 'None', neckline # 넥라인 가격 반환

def find_cup_and_handle(df, peaks, troughs, handle_drop_ratio=0.3):
    """컵 앤 핸들 (Cup and Handle) 패턴을 감지하고 넥라인 가격을 반환합니다."""
    recent_peaks = [p for p in peaks if p >= len(df) - 250]
    if len(recent_peaks) < 2: return False, None, None, None
    
    peak_right_idx = recent_peaks[-1]
    peak_right_price = df['Close'].iloc[peak_right_idx]
    
    handle_start_idx = peak_right_idx
    handle_max_drop = peak_right_price * (1 - handle_drop_ratio) # 핸들 최대 하락 허용치
    current_price = df['Close'].iloc[-1]
    neckline = peak_right_price # 컵의 오른쪽 끝 가격이 넥라인
    
    # 핸들 형성 조건 확인
    is_handle_forming = (df['Close'].iloc[handle_start_idx:].max() <= peak_right_price) # 핸들 구간 최고점이 컵 오른쪽 끝보다 낮아야 함
    is_handle_forming &= (current_price > handle_max_drop) # 현재 주가가 핸들 최대 하락치보다 높아야 함
    
    if is_handle_forming and current_price > neckline:
        return True, neckline, 'Breakout', neckline # 돌파
    if is_handle_forming and current_price <= neckline:
        return False, neckline, 'Potential', neckline # 잠재적 형성
        
    return False, neckline, 'None', neckline # 넥라인 가격 반환

# ==============================
# 6. 기술적 조건 및 패턴 분석
# ==============================

def check_ma_conditions(df, periods, analyze_patterns):
    """이동 평균선 조건 및 패턴 분석을 수행하고 결과를 딕셔너리로 반환합니다."""
    results = {}
    ma_cols = {20: 'SMA_20', 50: 'SMA_50', 200: 'SMA_200'}

    if len(df) < 200: analyze_patterns = False

    # 1. 주가와 MA 비교 (종가가 MA 위에 있는지)
    for p in periods:
        col_name = ma_cols.get(p)
        if col_name and col_name in df.columns and not df.empty:
            # 최종 종가가 해당 MA 값보다 높은지 확인
            results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[col_name].iloc[-1]
        else:
            results[f"above_ma{p}"] = False

    # 2. 골든/데드 크로스 감지 (50일선 vs 200일선)
    ma50_col = ma_cols.get(50)
    ma200_col = ma_cols.get(200)

    if ma50_col in df.columns and ma200_col in df.columns and len(df) >= 200:
        ma50_prev, ma50_curr = df[ma50_col].iloc[-2], df[ma50_col].iloc[-1]
        ma200_prev, ma200_curr = df[ma200_col].iloc[-2], df[ma200_col].iloc[-1]

        # 골든 크로스: 어제 50 < 200 이었고 오늘 50 > 200 인 경우
        results["goldencross_50_200_detected"] = (ma50_prev < ma200_prev and ma50_curr > ma200_curr)
        # 데드 크로스: 어제 50 > 200 이었고 오늘 50 < 200 인 경우
        results["deadcross_50_200_detected"] = (ma50_prev > ma200_prev and ma50_curr < ma200_curr)
    else:
        results["goldencross_50_200_detected"] = False
        results["deadcross_50_200_detected"] = False

    # 3. 기술적 패턴 분석 (pattern_type 필터 사용 시 활성화)
    if analyze_patterns:
        peaks, troughs = find_peaks_and_troughs(df)
        
        # 각 패턴 감지 함수 실행 (상태, 넥라인 가격 반환)
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

def analyze_symbol(item, periods, analyze_patterns, pattern_type_filter):
    """단일 종목을 분석하고 필터링 조건에 맞는지 확인하여 결과를 반환합니다."""
    code = item.get("Code") or item.get("code")
    name = item.get("Name") or item.get("name")
    path = DATA_DIR / f"{code}.parquet"

    if not path.exists():
        logging.debug(f"[{code}] 데이터 파일 없음.")
        return None

    try:
        # 데이터 로드 및 전처리
        df_raw = pd.read_parquet(path)
        if df_raw.index.dtype != 'datetime64[ns]' and 'Date' in df_raw.columns:
            df_raw = df_raw.set_index('Date')
            
        if df_raw.empty or len(df_raw) < 250:
            logging.debug(f"[{code}] 데이터 부족 ({len(df_raw)}일).")
            return None

        # 특징 공학 및 시장 국면 클러스터링
        df_full = calculate_advanced_features(df_raw)
        df_full = add_market_regime_clustering(df_full)
        
        df_analyze = df_full.iloc[-250:].copy() # 분석은 최근 250일 기준으로 수행

        if len(df_analyze) < 200: 
            logging.debug(f"[{code}] 최종 분석 데이터 부족 ({len(df_analyze)}일).")
            return None

        # 기술적 조건 및 패턴 분석 실행
        analysis_results = check_ma_conditions(df_analyze, periods, analyze_patterns)

        # 필터링 로직 적용
        is_match = True
        if pattern_type_filter:
            if pattern_type_filter == 'goldencross':
                is_match = analysis_results.get("goldencross_50_200_detected", False)
            elif pattern_type_filter == 'deadcross': # ⭐ 데드크로스 필터
                is_match = analysis_results.get("deadcross_50_200_detected", False)
            elif pattern_type_filter in ['double_bottom', 'triple_bottom', 'cup_and_handle']:
                status_key = f'pattern_{pattern_type_filter}_status'
                status = analysis_results.get(status_key)
                is_match = status in ['Breakout', 'Potential'] # 돌파 또는 잠재적 형성일 때 일치
            elif pattern_type_filter.startswith('regime:'): # ⭐ 시장 국면 필터 (regime:0, regime:1 등)
                if 'market_regime' in analysis_results:
                    try:
                        target_regime = int(pattern_type_filter.split(':')[1])
                        current_regime = analysis_results['market_regime']
                        is_match = (current_regime == target_regime)
                    except ValueError:
                        is_match = False
                else:
                    is_match = False
            elif pattern_type_filter == 'ma': # 종가가 모든 지정 MA 위에 있는 필터
                is_match = all(analysis_results.get(f"above_ma{p}", False) for p in periods)
            elif pattern_type_filter == 'all_below_ma': # 종가가 모든 지정 MA 아래에 있는 필터
                is_match = all(
                    (df_analyze['Close'].iloc[-1] < df_analyze.get(f'SMA_{p}', df_analyze.get(f'ma{p}', 0)).iloc[-1])
                    for p in periods if p in [20, 50, 200]
                )
            else:
                is_match = False

        if pattern_type_filter and not is_match: 
            logging.debug(f"[{code}] 필터 '{pattern_type_filter}' 불일치.")
            return None # 필터 조건 불만족 시 결과 반환 안 함

        if analysis_results:
            analysis_clean = {k: v for k, v in analysis_results.items() if v is not None}
            sort_score = analysis_clean.get('market_regime', -1) # 시장 국면으로 정렬 점수 사용
            
            return {
                "ticker": code,
                "name": name,
                "technical_conditions": analysis_clean, # 모든 분석 결과 포함
                "sort_score": sort_score 
            }
        return None
    except Exception as e:
        logging.error(f"[ERROR] {code} {name} 분석 실패: {e}\n{traceback.format_exc()}")
        return None

# ==============================
# 7. 분석 실행 및 캐싱 로직 (생략 없는 버전)
# ==============================

def cleanup_old_cache():
    """오늘 날짜 이전의 모든 JSON 캐시 파일을 삭제합니다."""
    today_str = datetime.now().strftime("%Y%m%d")
    logging.info(f"캐시 정리 시작 (오늘 날짜: {today_str})")
    cache_files = glob.glob(str(CACHE_DIR / "*.json"))
    deleted_count = 0
    for file_path in cache_files:
        path = Path(file_path)
        file_name_prefix = path.name[:8]
        if file_name_prefix.isdigit() and len(file_name_prefix) == 8:
            file_date_str = file_name_prefix
            if file_date_str < today_str:
                try:
                    os.remove(path)
                    deleted_count += 1
                except Exception as e:
                    logging.error(f"캐시 파일 {path.name} 삭제 실패: {e}")
    logging.info(f"캐시 정리 완료. 총 {deleted_count}개 파일 삭제됨.")

def run_analysis(workers, ma_periods_str, analyze_patterns, pattern_type_filter, top_n):
    """병렬 처리를 이용해 전체 종목 분석을 실행하고, 일일 캐싱을 적용합니다."""
    
    cleanup_old_cache() 
    
    start_time = time.time()
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]

    today_str = datetime.now().strftime("%Y%m%d")
    cache_filter = pattern_type_filter or 'ma_only'
    cache_key = f"{today_str}_{cache_filter.replace(':', '_')}_{top_n}.json" 
    cache_path = CACHE_DIR / cache_key
    
    # 2. 캐시 확인 및 로드: 같은 조건으로 분석된 오늘 날짜의 캐시가 있으면 사용
    if cache_path.exists():
        try:
            with open(cache_path, 'r', encoding='utf-8') as f:
                cached_data = json.load(f)
            logging.info(f"캐시 로드 성공: {cache_key}")
            # 캐시 결과를 stdout으로 바로 출력하고 종료
            sys.stdout.write(json.dumps(cached_data, ensure_ascii=False, indent=None, separators=(',', ':'), cls=CustomJsonEncoder) + "\n")
            sys.stdout.flush()
            sys.exit(0)
        except Exception as e:
            logging.error(f"캐시 파일 로드/파싱 실패: {e}. 재분석을 시도합니다.")

    # 3. 캐시 미스: 분석 실행 준비
    # 패턴 필터가 사용되면 패턴 분석을 강제 활성화
    if pattern_type_filter and pattern_type_filter not in ['ma', 'all_below_ma'] and not pattern_type_filter.startswith('regime:'):
        analyze_patterns = True

    # 50일선, 200일선은 크로스 감지를 위해 periods에 포함되도록 처리
    if 50 not in periods: periods.append(50)
    if 200 not in periods: periods.append(200)

    items = load_listing()
    initial_item_count = len(items)
    results = []
    logging.info(f"분석 시작 (캐시 미스): 총 {initial_item_count} 종목, 필터: {pattern_type_filter or 'None'}")
    processed_count = 0

    # 스레드 풀을 이용한 병렬 분석
    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_item = {
            executor.submit(analyze_symbol, item, periods, analyze_patterns, pattern_type_filter): item
            for item in items
        }

        for future in as_completed(future_to_item):
            processed_count += 1
            progress_percent = round((processed_count / initial_item_count) * 100, 2)

            # 진행 상황 JSON 출력
            sys.stdout.write(json.dumps({
                "mode": "progress",
                "total_symbols": initial_item_count,
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

    # 4. 결과 정렬 및 상위 N개 선택
    results.sort(key=lambda x: x.get('sort_score', -1), reverse=True)
    final_results = results[:top_n] if top_n > 0 else results
    
    for r in final_results:
        r.pop('sort_score', None) # 최종 출력에서 정렬 점수는 제거

    end_time = time.time()

    data_check = {
        "listing_file_exists": LISTING_FILE.exists(),
        "total_symbols_loaded": initial_item_count,
        "symbols_filtered": len(results),
        "symbols_returned": len(final_results),
        "time_taken_sec": round(end_time - start_time, 2),
    }

    # 5. 캐시 저장
    final_output = {
        "results": final_results,
        "mode": "analyze_result",
        "filter": pattern_type_filter or 'ma_only',
        "data_check": data_check
    }
    try:
        with open(cache_path, 'w', encoding='utf-8') as f:
            json.dump(final_output, f, ensure_ascii=False, cls=CustomJsonEncoder, indent=None, separators=(',', ':'))
        logging.info(f"분석 결과 캐시 저장 완료: {cache_key}")
    except Exception as e:
        logging.error(f"캐시 파일 저장 실패: {e}")

    # 6. 최종 결과 출력
    logging.info(f"분석 완료 및 결과 반환. 총 소요 시간: {data_check['time_taken_sec']}초")
    safe_print_json(final_output, status_code=0)

# ==============================
# 8. 차트 생성 로직 (모든 패턴 시각화 포인트 추가)
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
        
        # 날짜 인덱스 설정 확인
        if df.index.dtype != 'datetime64[ns]' and 'Date' in df.columns:
            df = df.set_index('Date')
        
        if df.empty:
            safe_print_json({"error": "데이터프레임이 비어 있습니다."}, status_code=1)
            return

        # 특징 공학 후 차트 기간만큼 슬라이싱
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
                "o": row['Open'],
                "h": row['High'],
                "l": row['Low'],
                "c": row['Close'],
                "v": row['Volume']
            })

        # 2. 이동평균선(MA) 데이터 포맷팅
        ma_data = {}
        for p in periods:
            ma_col_name = f'SMA_{p}'
            # MA 값이 없는 경우를 대비하여 다시 계산 (calculate_advanced_features에서 이미 계산됨)
            if ma_col_name not in df_for_chart.columns:
                 df_for_chart[ma_col_name] = df_for_chart['Close'].rolling(window=p, min_periods=1).mean()

            ma_values = []
            for index, row in df_for_chart.iterrows():
                if not pd.isna(row[ma_col_name]):
                    ma_values.append({
                        "x": index.strftime('%Y-%m-%d'),
                        "y": row[ma_col_name]
                    })
            ma_data[f"MA{p}"] = ma_values
        
        # 3. MACD 데이터 포맷팅
        macd_data = {
            "MACD": [], 
            "Signal": [], 
            "Histogram": []
        }
        for index, row in df_for_chart.iterrows():
            date_str = index.strftime('%Y-%m-%d')
            if not pd.isna(row['MACD']):
                macd_data["MACD"].append({"x": date_str, "y": row['MACD']})
            if not pd.isna(row['MACD_Signal']):
                macd_data["Signal"].append({"x": date_str, "y": row['MACD_Signal']})
            if not pd.isna(row['MACD_Hist']):
                macd_data["Histogram"].append({"x": date_str, "y": row['MACD_Hist']})

        # ⭐ 4. 크로스 지점 감지 및 패턴 넥라인 정보 추가
        cross_data = []
        pattern_data = []
        
        ma50_col = 'SMA_50'
        ma200_col = 'SMA_200'
        
        # 4-1. MA 크로스 지점 감지
        if ma50_col in df_for_chart.columns and ma200_col in df_for_chart.columns:
            ma_cross = df_for_chart[ma50_col] > df_for_chart[ma200_col]
            cross_points = ma_cross[ma_cross != ma_cross.shift(1)] # 상태가 바뀐 지점 (크로스 발생 지점)

            for date, is_above in cross_points.items():
                if date == df_for_chart.index[0]: continue
                prev_above = ma_cross.shift(1).loc[date]
                cross_type = ""
                
                if not prev_above and is_above: cross_type = "GoldenCross" # 아래에서 위로 돌파
                elif prev_above and not is_above: cross_type = "DeadCross" # 위에서 아래로 돌파
                
                if cross_type:
                    # 크로스 발생 날짜와 종가 기록
                    cross_data.append({
                        "x": date.strftime('%Y-%m-%d'),
                        "y": df_for_chart.loc[date, 'Close'],
                        "type": cross_type
                    })

        # 4-2. 패턴 넥라인 정보 감지
        # 패턴 감지 함수는 df_full (전체 데이터)를 사용합니다.
        peaks_all, troughs_all = find_peaks_and_troughs(df_full)
        
        # 각 패턴의 넥라인 가격과 상태를 얻어옴
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

        # 차트 시각화를 위해 넥라인 가격 정보를 포맷팅
        for p_name, p_neckline, p_status in patterns_to_check:
            # 넥라인이 유효하고 차트 가격 범위 내에 있을 때만 추가
            if p_neckline and (chart_min_close * 0.95 < p_neckline < chart_max_close * 1.05):
                pattern_data.append({
                    "x": today_date, # 넥라인은 가격 레벨이므로, 차트 끝 날짜에 표시할 가격으로 사용
                    "y": p_neckline,
                    "type": p_name,
                    "status": p_status
                })


        # 5. 최종 결과 JSON 구성
        final_output = {
            "ticker": code,
            "name": name,
            "mode": "chart_data",
            "ohlcv_data": ohlcv_data,
            "ma_data": ma_data,
            "macd_data": macd_data,
            "cross_points": cross_data,      # MA 크로스 지점 정보
            "pattern_points": pattern_data   # 패턴 넥라인 정보
        }

        safe_print_json(final_output, status_code=0)

    except Exception as e:
        logging.error(f"[ERROR] Chart.js 데이터 생성 실패 ({code} {name}): {e}\n{traceback.format_exc()}")
        safe_print_json({"error": f"Chart.js 데이터 생성 실패: {e}"}, status_code=1)

def main():
    """스크립트의 메인 실행 함수입니다. 인수를 파싱하고 모드별 함수를 호출합니다."""
    parser = argparse.ArgumentParser(description="주식 데이터 분석 및 차트 데이터 생성 스크립트")
    parser.add_argument("--mode", type=str, required=True, choices=['analyze', 'chart'], help="실행 모드 선택: 'analyze' 또는 'chart'")
    parser.add_argument("--workers", type=int, default=os.cpu_count() * 2, help="분석 모드에서 사용할 최대 스레드 수")
    parser.add_argument("--ma_periods", type=str, default="20,50,200", help="이동 평균선 기간 지정 (쉼표로 구분, 예: 5,20,50)")
    parser.add_argument("--chart_period", type=int, default=250, help="차트 모드에서 표시할 거래일 수 (기본값: 250일)")
    parser.add_argument("--symbol", type=str, help="차트 모드에서 사용할 종목 코드")
    parser.add_argument("--analyze_patterns", action="store_true", help="패턴 감지 활성화")
    parser.add_argument("--pattern_type", type=str,
                         choices=['ma', 'all_below_ma', 'double_bottom', 'triple_bottom', 'cup_and_handle', 'goldencross', 'deadcross', 'regime:0', 'regime:1', 'regime:2', 'regime:3'],
                         help="분석 모드에서 필터링할 패턴 종류 (예: goldencross, regime:0)")
    parser.add_argument("--debug", action="store_true", help="디버그 모드 활성화 (로깅 레벨 DEBUG)")
    parser.add_argument("--top_n", type=int, default=10, help="분석 결과 중 상위 N개 종목만 반환 (0 이하: 전체 반환)")

    args = parser.parse_args()
    
    log_level = logging.DEBUG if args.debug else logging.INFO
    setup_env(log_level)

    try:
        if args.mode == 'analyze':
            run_analysis(args.workers, args.ma_periods, args.analyze_patterns, args.pattern_type, args.top_n)
        elif args.mode == 'chart':
            if not args.symbol:
                safe_print_json({
                    "error": "CRITICAL_ERROR",
                    "reason": "차트 모드에는 --symbol 인수가 필수입니다.",
                    "mode": "argument_check"
                })
            generate_chart(args.symbol, args.ma_periods, args.chart_period)
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