# -*- coding: utf-8 -*-
"""
📘 athena_k_market_ai_prod.py (v1.0)
--------------------------------------------
✅ 한국 주식 시장 데이터 분석 및 기술적 패턴 감지 스크립트
    - 기능: 종목 분석 필터링 (analyze 모드), 차트 시각화 데이터 생성 (chart 모드)
    - 수정: --symbol 인자를 통한 단일 종목 분석 기능 추가
    - 추가: 'half_cup' (그릇 허리) 로직 추가 - 완성된 컵 모양은 제외하고 허리 구간만 추출
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
# → 상위 2단계로 올라가면 /MyBaseLinkV2/python
BASE_DIR = Path(__file__).resolve().parents[2]
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data" 
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json" 
CACHE_DIR = BASE_DIR / "cache" 
LOG_FILE = LOG_DIR / "stock_analyzer_ultimate.log"


# ==============================
# 3. 환경 초기화 및 유틸리티
# ==============================

def setup_env(log_level=logging.INFO):
    """환경 디렉토리를 설정하고 로깅을 초기화합니다."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    
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


# =====================================================================================
# 고급 특징 공학 및 클러스터링 로직
# =====================================================================================

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


# =====================================================================================
# 1. MA장기하락추세 기술적 분석 패턴 로직 
# =====================================================================================
def find_long_term_down_trend(df):
    """
    ★ 형님의 'MA 장기 하락 추세': 
    - 200일선이 완벽하게 우하향하고, 주가가 그 아래에서 계속 처박히는 종목
    """
    if len(df) < 250: return False, 0, 'None', 0
    
    curr = df.iloc[-1]
    prev_20 = df.iloc[-20]
    
    # 200일선 우하향 여부
    is_ma200_down = curr['SMA_200'] < prev_20['SMA_200']
    # 완전 역배열 (현재가 < 20 < 50 < 200)
    is_perfect_reverse = (curr['Close'] < curr['SMA_20'] < curr['SMA_50'] < curr['SMA_200'])
    # 200일선과의 이격도 (얼마나 많이 떨어졌나)
    drop_dist = (curr['SMA_200'] - curr['Close']) / curr['SMA_200']
    
    if is_ma200_down and is_perfect_reverse and drop_dist > 0.20:
        return True, curr['SMA_200'], 'Downward', drop_dist * 100
        
    return False, 0, 'None', 0
    
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


# =====================================================================================
# 2. 이중 바닥 기술적 분석 패턴 로직 
# =====================================================================================

def find_double_bottom(df, troughs, tolerance=0.02, min_duration=20):
    """
    최근 1년 중 최저가 부근(5% 이내)에서만 형성된 꼭짓점만 인정합니다.
    """
    if len(df) < 250: return False, None, 'None', 0
    
    recent_df = df.iloc[-250:]
    absolute_low = recent_df['Low'].min() # 최근 1년 전체 최저점
    
    recent_troughs = [t for t in troughs if t >= len(df) - 150]
    if len(recent_troughs) < 2: return False, None, 'None', 0
    
    idx2, idx1 = recent_troughs[-1], recent_troughs[-2]
    p1, p2 = df['Close'].iloc[idx1], df['Close'].iloc[idx2]
    
    # [핵심] 꼭짓점이 거의 바닥인가? (최저가 대비 5% 이내만 허용)
    is_at_absolute_bottom = (p1 <= absolute_low * 1.05) and (p2 <= absolute_low * 1.05)
    if not is_at_absolute_bottom: return False, None, 'None', 0

    # 바닥끼리의 가격 일치성 (오차 2% 이내로 초정밀)
    if abs(p1 - p2) / min(p1, p2) > tolerance: return False, None, 'None', 0
    
    interim_high = df['Close'].iloc[idx1:idx2].max()
    current_price = df['Close'].iloc[-1]
    
    # 넥라인 근처에서 이제 막 고개 드는 종목만
    if interim_high * 0.85 <= current_price <= interim_high * 1.10:
        return True, interim_high, 'Potential', interim_high
    
    return False, None, 'None', 0


# =====================================================================================
# 3. 삼중 바닥 기술적 분석 패턴 로직 
# =====================================================================================
def find_triple_bottom(df, troughs, tolerance=0.03):
    """
    세 개의 빨간 동그라미가 모두 1년 최저가 수준에 정렬되어야 합니다.
    """
    if len(df) < 250: return False, None, 'None', 0
    
    recent_df = df.iloc[-250:]
    absolute_low = recent_df['Low'].min()
    
    recent_troughs = [t for t in troughs if t >= len(df) - 200]
    if len(recent_troughs) < 3: return False, None, 'None', 0
    
    idx3, idx2, idx1 = recent_troughs[-1], recent_troughs[-2], recent_troughs[-3]
    prices = [df['Close'].iloc[idx1], df['Close'].iloc[idx2], df['Close'].iloc[idx3] ]
    
    # [핵심] 세 꼭짓점 모두 바닥권인가?
    if not all(p <= absolute_low * 1.07 for p in prices): return False, None, 'None', 0
    
    # 세 바닥의 수평 유지 (형님 그림처럼 일직선)
    if (max(prices) - min(prices)) / min(prices) > tolerance: return False, None, 'None', 0
    
    neckline = df['Close'].iloc[idx1:idx3].max()
    current_price = df['Close'].iloc[-1]
    
    if neckline * 0.8 <= current_price <= neckline * 1.15:
        return True, neckline, 'Potential', neckline
    
    return False, None, 'None', 0

# =====================================================================================
# 4. 컵 앤 핸들 기술적 분석 패턴 로직 
# =====================================================================================

def find_cup_and_handle(df, peaks, troughs, handle_drop_ratio=0.3):
    """
    ★ 형님 전용 컵 앤 핸들 (Early-Stage Cup)
    이미 완성된 컵은 제외하고, 급락 후 바닥을 다진 뒤 
    이제 막 '오른쪽 손잡이'를 만들려는 초기 종목을 포착합니다.
    """
    if len(df) < 250: return False, None, 'None', 0
    
    recent_250 = df.iloc[-250:]
    # 1. 컵의 시작점 (급락 전 언덕)과 바닥점 확인
    peak_price = recent_250['High'].max()     # 컵의 왼쪽 끝
    trough_price = recent_250['Low'].min()    # 컵의 바닥
    current_price = df['Close'].iloc[-1]
    
    # 2. 컵의 깊이 검증 (형님 그림처럼 깊게 파여야 함)
    cup_depth_pct = (peak_price - trough_price) / peak_price
    if cup_depth_pct < 0.30: return False, None, 'None', 0
    
    # 3. 'U자'가 되기 전 시작 지점 포착 (회복률 30% ~ 60% 구간)
    # 전고점을 뚫으러 가는 80~90% 구간은 너무 늦었으므로 배제
    recovery_rate = (current_price - trough_price) / (peak_price - trough_price)
    
    # 형님 그림판의 "U자가 되기 전" 구간 (핸들 형성 초입)
    is_early_cup = (0.25 <= recovery_rate <= 0.55)
    
    if is_early_cup:
        # 최근 20일간의 움직임이 바닥을 탈출하여 완만한 상승 곡선을 그리는지 확인
        sma20 = df['SMA_20'].iloc[-1]
        sma50 = df['SMA_50'].iloc[-1]
        
        # 20일선이 고개를 들고 주가가 그 위에 안착했을 때가 찐 핸들 자리
        if current_price > sma20:
            # 랭킹 점수: 컵이 깊고 횡보가 적절했을수록 높은 점수
            c_score = (cup_depth_pct * 100) + (recovery_rate * 50)
            return True, peak_price, 'Potential', c_score

    return False, None, 'None', 0

# =====================================================================================
# 5. 하프앤핸들 기술적 분석 패턴 로직 
# =====================================================================================
def find_half_cup_waist(df):
    """
    ★ 형님의 'L자형 바닥 탈출' 그림 반영
    - 수정: 하락폭이 크고 바닥 횡보가 길수록 높은 랭킹 점수(l_score) 부여
    """
    if len(df) < 250: return False, None, 'None', 0
    
    recent_250 = df.iloc[-250:]
    peak_price = recent_250['High'].max()     # 전고점
    trough_price = recent_250['Low'].min()    # 최저점
    current_price = df['Close'].iloc[-1]
    
    total_drop = peak_price - trough_price
    if total_drop <= 0: return False, None, 'None', 0
    
    total_drop_pct = total_drop / peak_price
    recovery_rate = (current_price - trough_price) / total_drop
    
    # 1. 찐 바닥 구간 필터링 (회복률 10%~35%로 더 보수적으로 잡음)
    is_waist_zone = (0.10 <= recovery_rate <= 0.35) 
    is_not_complete_cup = (current_price < peak_price * 0.70)
    
    if is_waist_zone and is_not_complete_cup:
        # 횡보성 계산: 최근 40일간 바닥권에 머문 비율
        bottom_threshold = trough_price * 1.15
        days_at_bottom = (recent_250['Close'].iloc[-40:] <= bottom_threshold).sum()
        
        # ★ L-Score: (하락률 * 100) + (횡보일수 * 2) -> 이게 높을수록 정렬 상단
        l_score = (total_drop_pct * 100) + (days_at_bottom * 2)
        
        sma20 = df['SMA_20'].iloc[-1]
        # 고개 살짝 들기 (20일선 근처)
        if current_price > sma20 * 0.98:
            return True, peak_price, 'Potential', l_score
             
    return False, None, 'None', 0
    
# =====================================================================================
# 6. 기술적 조건 및 패턴 분석
# =====================================================================================

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
        _, _, tb_status, _ = find_triple_bottom(df, troughs)
        _, _, ch_status, ch_price = find_cup_and_handle(df, peaks, troughs)
        
        # ★ 허리 구간 감지 (컵 모양 제외)
        _, _, hc_status, l_score = find_half_cup_waist(df)

        results['pattern_double_bottom_status'] = db_status
        results['db_neckline_price'] = db_price

        results['pattern_triple_bottom_status'] = tb_status

        results['pattern_cup_and_handle_status'] = ch_status
        results['ch_neckline_price'] = ch_price
        
        # ★ 허리 구간 결과 추가
        results['pattern_half_cup_status'] = hc_status
        results['hc_l_score'] = l_score # 정렬용 점수 보관

    # 4. 시장 국면 (Market Regime)
    if 'MarketRegime' in df.columns and not df.empty:
        results['market_regime'] = int(df['MarketRegime'].iloc[-1])
    else:
        results['market_regime'] = -1

    return results


# ==============================
# 7. 분석 실행 및 캐싱 로직
# ==============================

def analyze_symbol(item, periods, analyze_patterns, pattern_type_filter, symbol_filter=None): 
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
            elif pattern_type_filter in ['double_bottom', 'triple_bottom', 'cup_and_handle', 'half_cup']:
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
                is_match = all(analysis_results.get(f"above_ma{p}", False) for p in periods if p in [20, 50, 200]) 
            elif pattern_type_filter == 'all_below_ma':
                is_match = all(
                    (df_analyze['Close'].iloc[-1] < df_analyze.get(f'SMA_{p}', df_analyze.get(f'ma{p}', 0)).iloc[-1])
                    for p in periods if p in [20, 50, 200]
                )
            else:
                is_match = False

        if pattern_type_filter and not is_match: 
            return None

        if analysis_results:
            analysis_clean = {k: v for k, v in analysis_results.items() if v is not None}
            # ★ 하프컵 분석일 때는 l_score로 정렬, 그 외엔 market_regime으로 정렬
            sort_score = analysis_clean.get('hc_l_score', 0) if pattern_type_filter == 'half_cup' else analysis_clean.get('market_regime', -1)
            
            return {
                "ticker": code,
                "name": name,
                "technical_conditions": analysis_clean, 
                "sort_score": sort_score 
            }
        return None
    except Exception as e:
        logging.error(f"[ERROR] {code} {name} 분석 실패: {e}\n{traceback.format_exc()}")
        return None

def run_analysis(workers, ma_periods_str, analyze_patterns_flag, pattern_type_filter, top_n, symbol_filter=None): 
    """병렬 처리를 이용해 전체 종목 분석을 실행하고, 일일 캐싱을 적용합니다."""
    
    cleanup_old_cache() 
    
    start_time = time.time()
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]

    today_str = datetime.now().strftime("%Y%m%d")
    analyze_patterns = analyze_patterns_flag or (pattern_type_filter not in [None, 'ma', 'all_below_ma'] and not pattern_type_filter.startswith('regime:'))
    
    cache_filter_key = f"{pattern_type_filter or 'ma_only'}_{'pattern' if analyze_patterns else 'no_pattern'}"
    cache_key = f"{today_str}_{cache_filter_key.replace(':', '_')}_{top_n}.json" 
    cache_path = CACHE_DIR / cache_key
    
    if not symbol_filter and cache_path.exists(): 
        try:
            with open(cache_path, 'r', encoding='utf-8') as f:
                cached_data = json.load(f)
            sys.stdout.write(json.dumps(cached_data, ensure_ascii=False, indent=None, separators=(',', ':'), cls=CustomJsonEncoder) + "\n")
            sys.stdout.flush()
            sys.exit(0)
        except Exception: pass

    if 50 not in periods: periods.append(50)
    if 200 not in periods: periods.append(200)

    items = load_listing()
    if symbol_filter:
        items = [item for item in items if (item.get("Code") or item.get("code")) == symbol_filter]
    
    initial_item_count = len(items) 
    if initial_item_count == 0:
        safe_print_json({"error": "DATA_EMPTY"}, status_code=1)
        return

    results = []
    processed_count = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_item = {
            executor.submit(analyze_symbol, item, periods, analyze_patterns, pattern_type_filter): item
            for item in items
        }

        for future in as_completed(future_to_item):
            processed_count += 1
            progress_percent = round((processed_count / initial_item_count) * 100, 2) 
            sys.stdout.write(json.dumps({"mode": "progress", "progress_percent": progress_percent}, ensure_ascii=False) + "\n")
            sys.stdout.flush()

            try:
                r = future.result()
                if r: results.append(r)
            except Exception: pass

    # ★ 정렬 로직: sort_score 기준 내림차순 (L자형 점수 높은 게 위로!)
    results.sort(key=lambda x: x.get('sort_score', -1), reverse=True)
    final_results = results[:top_n] if top_n > 0 else results
    
    for r in final_results:
        r.pop('sort_score', None)

    final_output = {
        "results": final_results,
        "mode": "analyze_result",
        "filter": pattern_type_filter or 'ma_only'
    }
    
    if not symbol_filter:
        try:
            with open(cache_path, 'w', encoding='utf-8') as f:
                json.dump(final_output, f, ensure_ascii=False, cls=CustomJsonEncoder, indent=None, separators=(',', ':'))
        except Exception: pass

    safe_print_json(final_output, status_code=0)


# ==============================
# 8. 차트 생성 로직
# ==============================

def generate_chart(symbol, ma_periods_str, chart_period):
    """단일 종목의 시계열 데이터를 Chart.js JSON 포맷으로 변환하여 반환합니다."""
    code = symbol
    name = get_stock_name(code)
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()] 
    path = DATA_DIR / f"{code}.parquet"

    if not path.exists():
        safe_print_json({"error": "FILE_NOT_FOUND"}, status_code=1)
        return

    try:
        df = pd.read_parquet(path)
        if df.index.dtype != 'datetime64[ns]' and 'Date' in df.columns:
            df = df.set_index('Date')

        df_full = calculate_advanced_features(df)
        df_for_chart = df_full.iloc[-chart_period:].copy()

        ohlcv_data = [{"x": idx.strftime('%Y-%m-%d'), "o": r['Open'], "h": r['High'], "l": r['Low'], "c": r['Close'], "v": r['Volume']} for idx, r in df_for_chart.iterrows()]
        
        ma_data = {}
        for p in periods:
            ma_col = f'SMA_{p}'
            if ma_col not in df_for_chart.columns:
                df_for_chart[ma_col] = df_for_chart['Close'].rolling(window=p, min_periods=1).mean()
            ma_data[f"MA{p}"] = [{"x": idx.strftime('%Y-%m-%d'), "y": r[ma_col]} for idx, r in df_for_chart.iterrows() if not pd.isna(r[ma_col])]
        
        macd_data = {
            "MACD": [{"x": i.strftime('%Y-%m-%d'), "y": r['MACD']} for i, r in df_for_chart.iterrows()],
            "Signal": [{"x": i.strftime('%Y-%m-%d'), "y": r['MACD_Signal']} for i, r in df_for_chart.iterrows()],
            "Histogram": [{"x": i.strftime('%Y-%m-%d'), "y": r['MACD_Hist']} for i, r in df_for_chart.iterrows()]
        }

        # 패턴 및 크로스 지점
        peaks_all, troughs_all = find_peaks_and_troughs(df_full)
        _, db_neckline, db_status, _ = find_double_bottom(df_full, troughs_all)
        _, tb_neckline, tb_status, _ = find_triple_bottom(df_full, troughs_all)
        _, ch_neckline, ch_status, _ = find_cup_and_handle(df_full, peaks_all, troughs_all)
        _, hc_neckline, hc_status, _ = find_half_cup_waist(df_full)

        pattern_data = []
        today_date = df_full.index[-1].strftime('%Y-%m-%d')
        for p_name, p_neck, p_stat in [("DoubleBottom", db_neckline, db_status), ("TripleBottom", tb_neckline, tb_status), ("CupAndHandle", ch_neckline, ch_status), ("HalfCup", hc_neckline, hc_status)]:
            if p_neck: pattern_data.append({"x": today_date, "y": p_neck, "type": p_name, "status": p_stat})

        safe_print_json({
            "ticker": code, "name": name, "mode": "chart_data",
            "ohlcv_data": ohlcv_data, "ma_data": ma_data, "macd_data": macd_data, "pattern_points": pattern_data
        }, status_code=0)

    except Exception:
        safe_print_json({"error": "CHART_FAIL"}, status_code=1)


def main():
    parser = argparse.ArgumentParser(description="주식 데이터 분석 및 차트 데이터 생성 스크립트")
    parser.add_argument("--mode", type=str, required=True, choices=['analyze', 'chart'])
    parser.add_argument("--workers", type=int, default=os.cpu_count() * 2)
    parser.add_argument("--ma_periods", type=str, default="20,50,200")
    parser.add_argument("--chart_period", type=int, default=250)
    parser.add_argument("--symbol", type=str)
    parser.add_argument("--analyze_patterns", action="store_true")
    parser.add_argument("--pattern_type", type=str, choices=['ma', 'all_below_ma', 'double_bottom', 'triple_bottom', 'cup_and_handle', 'half_cup', 'goldencross', 'deadcross', 'regime:0', 'regime:1', 'regime:2', 'regime:3'])
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--top_n", type=int, default=10)
    
    args = parser.parse_args()
    setup_env(log_level=logging.DEBUG if args.debug else logging.INFO) 
    
    if args.mode == 'analyze':
        analyze_patterns_flag = args.analyze_patterns or (args.pattern_type not in [None, 'ma', 'all_below_ma'] and not (args.pattern_type and args.pattern_type.startswith('regime:')))
        run_analysis(args.workers, args.ma_periods, analyze_patterns_flag, args.pattern_type, args.top_n, args.symbol)
    elif args.mode == 'chart':
        if not args.symbol: return
        generate_chart(args.symbol, args.ma_periods, args.chart_period)

if __name__ == "__main__":
    main()