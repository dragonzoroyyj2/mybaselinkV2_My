# -*- coding: utf-8 -*-
"""
📘 athena_k_market_ai.py (v1.2 - FinanceDataReader/yfinance 안정화)
--------------------------------------------
✅ 한국 주식 시장 데이터 분석 및 패턴 감지 스크립트
✅ [FIXED] FinanceDataReader.financials AttributeError 해결 (기본적 분석 로직 변경)
✅ [FIXED] yfinance 뉴스 NoneType 에러 해결 (타임스탬프 안전 검사 추가)
✅ 초기 안전 검사, 병렬 처리, DART 공시/재무/뉴스 기반 악재 필터링 완비
✅ K-Means 클러스터링으로 시장 국면(Market Regime) 정의
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
# 1. 초기 안전 검사 및 필수 라이브러리 임포트
# ==============================

def safe_print_json(data, status_code=1):
    """
    표준 출력(stdout)으로 JSON을 안전하게 출력하고 프로세스를 종료합니다.
    (비정상 종료 시 status_code=1, 정상 완료 시 status_code=0)
    """
    sys.stdout.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    sys.stdout.flush()
    if status_code != 0:
        # 치명적 오류 발생 시 프로세스 즉시 종료
        sys.exit(status_code)

def check_internet_connection(host="8.8.8.8", port=53, timeout=3):
    """
    인터넷 연결 상태를 확인하는 함수 (Google DNS 서버 사용).
    """
    try:
        socket.setdefaulttimeout(timeout)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((host, port))
        s.close()
        return True
    except Exception:
        return False

# 치명적 초기 검사 1: 인터넷 연결 확인 및 즉시 종료
if not check_internet_connection():
    safe_print_json({
        "error": "CRITICAL_ERROR",
        "reason": "인터넷 연결을 확인할 수 없습니다. 네트워크 상태를 점검해주세요. (Google DNS 연결 실패)",
        "mode": "initial_check"
    })
# (safe_print_json 내부에서 sys.exit(1) 호출됨)


# 치명적 초기 검사 2: 필수 라이브러리 임포트 및 즉시 종료
try:
    import FinanceDataReader as fdr
    import pandas as pd
    import mplfinance as mpf
    import matplotlib.pyplot as plt
    import numpy as np
    from scipy.signal import find_peaks
    import yfinance as yf
    
    # 고급 분석용 라이브러리
    import ta # Technical Analysis Library
    from sklearn.preprocessing import StandardScaler
    from sklearn.cluster import KMeans
    
    # DART 공시 필터링 (환경 변수 확인)
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
# (safe_print_json 내부에서 sys.exit(1) 호출됨)


# ==============================
# 2. 경로 및 상수 설정
# ==============================
# BASE_DIR: 상위 2단계 경로 (예: /MyBaseLinkV2/python)
BASE_DIR = Path(__file__).resolve().parents[2]
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data"
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json"
LOG_FILE = LOG_DIR / "stock_analyzer_ultimate.log"

# ==============================
# 3. 환경 초기화 및 유틸리티
# ==============================
def setup_env():
    """환경 디렉토리를 설정하고 로깅을 초기화합니다."""
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    
    # 분석 속도를 위해 로깅 레벨을 WARNING으로 설정
    logging.basicConfig(
        level=logging.WARNING, 
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[
            logging.FileHandler(LOG_FILE, encoding="utf-8", mode='a'),
            logging.StreamHandler(sys.stdout)
        ]
    )

def set_korean_font():
    """Matplotlib 한글 폰트를 설정합니다."""
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
        logging.warning("한글 폰트 설정에 실패했습니다. 기본 폰트를 사용합니다.")

MPLFINANCE_FONT = 'sans-serif' 
set_korean_font()
setup_env() 

def load_listing():
    """종목 리스트 파일을 로드합니다."""
    if not LISTING_FILE.exists(): 
        logging.error(f"종목 리스트 파일 없음: {LISTING_FILE} -> 기본 종목(삼성전자)으로 대체합니다.")
        # DartCorpCode는 삼성전자 (005930)의 고유 번호입니다.
        return [{"Code": "005930", "Name": "삼성전자", "DartCorpCode": "00126380"}] 
    try:
        # 파일이 존재하면 로드
        with open(LISTING_FILE, "r", encoding="utf-8") as f: 
            return json.load(f)
    except Exception as e:
        logging.error(f"종목 리스트 파일 로드 실패: {e} -> 기본 종목(삼성전자)으로 대체합니다.")
        return [{"Code": "005930", "Name": "삼성전자", "DartCorpCode": "00126380"}]

def get_stock_name(symbol):
    """종목 코드로 이름을 찾습니다."""
    try:
        items = load_listing()
        for item in items:
            code = item.get("Code") or item.get("code")
            if code == symbol: return item.get("Name") or item.get("name")
        return symbol
    except Exception: return symbol

def get_dart_corp_code(symbol):
    """종목 코드로 DART 고유 번호를 찾습니다."""
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
    """고급 패턴 인식을 위해 기술적 지표를 특징(Feature)으로 추가합니다."""
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
    """기술적 특징을 기반으로 K-Means 클러스터링을 수행하여 시장 국면을 정의합니다."""
    feature_cols = ['RSI', 'MACD', 'BB_Width', 'TREND_CROSS']
    min_data_length = 50 
    
    if len(df) < min_data_length or not all(col in df.columns for col in feature_cols):
        logging.warning(f"클러스터링에 필요한 데이터 길이가 {min_data_length}일 미만입니다. ({len(df)}일)")
        df['MarketRegime'] = -1 # 데이터 부족 시 -1로 표시
        return df

    data = df[feature_cols].copy()
    
    scaler = StandardScaler()
    scaled_data = scaler.fit_transform(data)
    
    # K-Means 클러스터링 
    kmeans = KMeans(n_clusters=n_clusters, random_state=42, n_init=10)
    df['MarketRegime'] = kmeans.fit_predict(scaled_data)
    
    return df


# ==============================
# 5. 기술적 분석 패턴 로직 
# ==============================

def find_peaks_and_troughs(df, prominence=0.01, width=3):
    """주요 봉우리와 골짜기 인덱스 찾기 (최근 250일 기준)"""
    recent_df = df.iloc[-250:].copy()
    if recent_df.empty: return np.array([]), np.array([])
    
    # 가격 변동성(표준편차)을 기준으로 봉우리/골짜기 중요도 설정
    std_dev = recent_df['Close'].std()
    peaks, _ = find_peaks(recent_df['Close'], prominence=std_dev * prominence, width=width)
    troughs, _ = find_peaks(-recent_df['Close'], prominence=std_dev * prominence, width=width)
    
    # 전체 데이터프레임 인덱스로 변환
    start_idx = len(df) - len(recent_df)
    return peaks + start_idx, troughs + start_idx

def find_double_bottom(df, troughs, tolerance=0.05, min_duration=30):
    """이중 바닥 패턴 감지"""
    # 최근 250일 내의 골짜기만 사용
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 2: return False, None, None, None
    
    idx2, idx1 = recent_troughs[-1], recent_troughs[-2] 
    price1, price2 = df['Close'].iloc[idx1], df['Close'].iloc[idx2]
    
    if idx2 - idx1 < min_duration: return False, None, None, None # 최소 기간 충족
    
    # 바닥 가격이 허용 오차 내인지 확인
    is_price_matching = abs(price1 - price2) / min(price1, price2) < tolerance
    if not is_price_matching: return False, None, None, None
    
    interim_high = df['Close'].iloc[idx1:idx2].max() # 중간 봉우리
    current_price = df['Close'].iloc[-1]

    is_breakout = current_price > interim_high # 넥 라인 돌파
    
    if is_breakout: return True, interim_high, 'Breakout', interim_high
    
    # 잠재적 패턴 확인 (바닥에서 50% 이상 회복)
    retrace_ratio = (current_price - min(price1, price2)) / (interim_high - min(price1, price2)) if interim_high > min(price1, price2) else 0
    is_potential = retrace_ratio > 0.5 and current_price < interim_high 
    
    if is_potential: return False, interim_high, 'Potential', interim_high
        
    return False, None, None, None

def find_triple_bottom(df, troughs, tolerance=0.05, min_duration_total=75):
    """삼중 바닥 패턴 감지"""
    recent_troughs = [t for t in troughs if t >= len(df) - 250]
    if len(recent_troughs) < 3: return False, None, None, None
    
    idx3, idx2, idx1 = recent_troughs[-1], recent_troughs[-2], recent_troughs[-3]
    price1, price2, price3 = df['Close'].iloc[idx1], df['Close'].iloc[idx2], df['Close'].iloc[idx3]
    
    if idx3 - idx1 < min_duration_total: return False, None, None, None # 최소 기간 충족
    
    # 세 바닥 가격이 허용 오차 내인지 확인
    min_price = min(price1, price2, price3)
    max_price = max(price1, price2, price3)
    is_price_matching = (max_price - min_price) / min_price < tolerance
    if not is_price_matching: return False, None, None, None
    
    high1 = df['Close'].iloc[idx1:idx2].max()
    high2 = df['Close'].iloc[idx2:idx3].max()
    neckline = max(high1, high2) # 넥 라인 = 중간 봉우리 중 가장 높은 값
    current_price = df['Close'].iloc[-1]

    is_breakout = current_price > neckline # 넥 라인 돌파
    
    if is_breakout: return True, neckline, 'Breakout', neckline
    
    # 잠재적 패턴 확인
    retrace_ratio = (current_price - min_price) / (neckline - min_price) if neckline > min_price else 0
    is_potential = retrace_ratio > 0.5 and current_price < neckline
    
    if is_potential: return False, neckline, 'Potential', neckline
        
    return False, None, None, None


def find_cup_and_handle(df, peaks, troughs, handle_drop_ratio=0.3):
    """컵 앤 핸들 패턴 감지"""
    recent_peaks = [p for p in peaks if p >= len(df) - 250]
    if len(recent_peaks) < 2: return False, None, None, None
    
    peak_right_idx = recent_peaks[-1]
    peak_right_price = df['Close'].iloc[peak_right_idx]
    
    # 컵 모양 형성 확인 로직 (간단화)
    # 컵의 오른쪽 봉우리가 가장 최근 봉우리여야 함
    
    handle_start_idx = peak_right_idx 
    handle_max_drop = peak_right_price * (1 - handle_drop_ratio) # 핸들 최대 하락 깊이

    current_price = df['Close'].iloc[-1]
    
    # 핸들 형성 조건: 오른쪽 봉우리 이후 가격이 그 봉우리를 넘지 않고, 최대 하락 폭(30%) 이내에 있어야 함
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
    """yfinance를 이용해 기본적인 펀더멘털 지표 (P/E, P/B)를 가져옵니다.
    FinanceDataReader.financials 에러를 방지하기 위해 이 함수로 대체되었습니다.
    """
    fundamentals = {}
    try:
        # 한국 코스피/코스닥 종목은 .KS를 붙여야 함
        yf_ticker = f"{code}.KS" if not code.endswith('.KS') else code
        ticker = yf.Ticker(yf_ticker)
        info = ticker.info
        
        # P/E (Trailing PE)
        if 'trailingPE' in info and info['trailingPE'] is not None:
             fundamentals['PE_Ratio'] = info['trailingPE']
        
        # P/B (Price to Book)
        if 'priceToBook' in info and info['priceToBook'] is not None:
             fundamentals['PB_Ratio'] = info['priceToBook']

    except Exception as e:
        logging.warning(f"yfinance 펀더멘털 데이터 로드 실패 ({code}): {e}")
        
    return fundamentals

def get_yfinance_news(code):
    """yfinance를 이용해 최근 뉴스 헤드라인을 가져옵니다."""
    headlines = []
    try:
        # 한국 코스피/코스닥 종목은 .KS를 붙여야 함
        yf_ticker = f"{code}.KS" if not code.endswith('.KS') else code
        ticker = yf.Ticker(yf_ticker)
        news_list = ticker.news
        filtered_headlines = []
        two_months_ago = datetime.now() - timedelta(days=60)
        
        for news in news_list:
            publish_timestamp = news.get('providerPublishTime')
            
            # NoneType 에러 방지를 위해 'providerPublishTime'이 존재하는지 안전하게 확인
            if publish_timestamp is None:
                continue

            publish_date = datetime.fromtimestamp(publish_timestamp) 
            if publish_date >= two_months_ago:
                filtered_headlines.append({"title": news.get('title'), "link": news.get('link')})
            if len(filtered_headlines) >= 3: break # 최대 3개 헤드라인만 가져옴
        return filtered_headlines
    except Exception as e:
        # yfinance의 불안정성(예: API 변경)으로 인한 에러는 경고로 처리
        logging.warning(f"yfinance 뉴스 로드 실패 ({code}): {e}")
        return []

def get_fundamental_data(code):
    """기본적 분석 데이터를 가져옵니다."""
    # FDR 대신 yfinance 기반의 기본 펀더멘털 사용
    fundamentals = get_basic_fundamentals(code) 
    headlines = get_yfinance_news(code)
    return fundamentals, headlines

def check_for_negative_dart_disclosures(corp_code):
    """DART 공시에서 악재성 키워드 검사 (환경 변수 사용)"""
    if not DART_AVAILABLE or not corp_code or not DART_API_KEY: return False, None
    try:
        dart = Dart(DART_API_KEY)
        end_date = datetime.now()
        start_date = end_date - timedelta(days=60) # 최근 60일 공시
        reports = dart.search(corp_code=corp_code, start_dt=start_date.strftime('%Y%m%d'))
        
        # 악재성 키워드 목록
        negative_keywords = ["횡령", "배임", "소송 제기", "손해배상", "거래정지", "상장폐지", "감사의견 거절", "파산", "회생"]
        for report in reports:
            # 유상증자 중 제3자배정은 긍정적일 수 있으므로 악재 필터에서 제외
            if "유상증자 결정" in report.report_nm and "제3자배정" in report.report_nm: continue 
            if any(keyword in report.report_nm for keyword in negative_keywords):
                return True, f"DART 공시 악재: '{report.report_nm}'"
        return False, None
    except Exception as e:
        logging.error(f"DART 공시 확인 중 오류 ({corp_code}): {e}")
        return False, None

def check_for_negatives(fundamentals, headlines, code, corp_code):
    """뉴스/재무/공시 기반으로 악재성 종목 여부를 검사"""
    negative_keywords_news = ["횡령", "배임", "소송", "분쟁", "거래 정지", "악재", "하락 전망", "투자주의", "적자"]
    
    # 1. 뉴스 악재 확인
    for news in headlines:
        if any(keyword in news.get('title', '') for keyword in negative_keywords_news):
            return True, f"뉴스 악재: '{news.get('title')}'"
            
    # 2. 재무 악재 확인 (yfinance 기반 P/E, P/B 사용)
    pe_ratio = fundamentals.get('PE_Ratio')
    pb_ratio = fundamentals.get('PB_Ratio')
    
    # P/E 비율이 마이너스 (적자)인 경우 악재로 판단
    if pe_ratio is not None and not pd.isna(pe_ratio) and pe_ratio < 0: 
        return True, f"재무 악재: P/E {pe_ratio:.1f} (적자)"
    
    # P/B 비율이 1.0 미만 (장부가치보다 낮음)이더라도 다른 필터로 잡을 수 있으므로, 
    # 여기서는 극도로 낮은 밸류에이션만 확인 (예: 0.3 미만 등) - 단독 악재로 보기 어려워 일단 P/E만 사용

    # 3. DART 공시 악재 확인
    is_negative_dart, reason_dart = check_for_negative_dart_disclosures(corp_code)
    if is_negative_dart: return True, reason_dart
        
    return False, None

# ==============================
# 7. 분석 실행 및 필터링
# ==============================

def check_ma_conditions(df, periods, analyze_patterns):
    """이동 평균선 및 패턴 분석을 수행하고 결과를 반환합니다."""
    results = {}
    
    ma_cols = {20: 'SMA_20', 50: 'SMA_50', 200: 'SMA_200'}

    # 200일 미만 데이터는 패턴 분석에 부적합하다고 판단
    if len(df) < 200: analyze_patterns = False
        
    # 현재가 vs 이동 평균선 비교
    for p in periods:
        col_name = ma_cols.get(p)
        if col_name and col_name in df.columns:
            results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[col_name].iloc[-1]
        elif len(df) >= p:
             # 임시로 MA 계산 (analyze_symbol에서 이미 계산되었을 가능성 높음)
             df[f'ma{p}'] = df['Close'].rolling(window=p, min_periods=1).mean() 
             results[f"above_ma{p}"] = df['Close'].iloc[-1] > df[f'ma{p}'].iloc[-1]
    
    # 골든/데드 크로스 로직 (SMA_50 vs SMA_200)
    ma50_col = ma_cols.get(50)
    ma200_col = ma_cols.get(200)

    if ma50_col in df.columns and ma200_col in df.columns and len(df) >= 200:
        # 전날과 오늘 이동평균선 위치 비교
        ma50_prev, ma50_curr = df[ma50_col].iloc[-2], df[ma50_col].iloc[-1]
        ma200_prev, ma200_curr = df[ma200_col].iloc[-2], df[ma200_col].iloc[-1]

        # 골든 크로스: 50일선이 200일선 아래에서 위로 교차
        results["goldencross_50_200_detected"] = (ma50_prev < ma200_prev and ma50_curr > ma200_curr)
        # 데드 크로스: 50일선이 200일선 위에서 아래로 교차
        results["deadcross_50_200_detected"] = (ma50_prev > ma200_prev and ma50_curr < ma200_curr)
    else:
        results["goldencross_50_200_detected"] = False
        results["deadcross_50_200_detected"] = False
    
    # 패턴 분석 활성화 시
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
        
    if 'MarketRegime' in df.columns:
        # K-Means 클러스터링 결과는 정수형으로 저장
        results['market_regime'] = int(df['MarketRegime'].iloc[-1])

    return results

def analyze_symbol(item, periods, analyze_patterns, exclude_negatives, pattern_type_filter):
    """단일 종목을 분석하고 결과를 반환합니다."""
    code = item.get("Code") or item.get("code")
    name = item.get("Name") or item.get("name")
    corp_code = item.get("DartCorpCode")
    path = DATA_DIR / f"{code}.parquet"
    
    # 1. 데이터 로드 및 유효성 검사
    if not path.exists(): 
        logging.debug(f"[{code}] 데이터 파일 없음.")
        return None
    
    try:
        df_raw = pd.read_parquet(path)
        if df_raw.empty or len(df_raw) < 50: 
            logging.debug(f"[{code}] 데이터 부족 ({len(df_raw)}일).")
            return None
        
        # 2. 분석에 사용할 최근 250일 데이터 슬라이스
        df = df_raw.iloc[-250:].copy()

        # 3. 기술적 특징 공학 및 클러스터링
        df = calculate_advanced_features(df)
        if len(df) < 50: return None
        
        df = add_market_regime_clustering(df)
        
        # 4. 기본적 분석 및 뉴스 수집
        fundamentals, headlines = get_fundamental_data(code)
        
        # 5. 악재 필터링
        if exclude_negatives:
            is_negative, reason = check_for_negatives(fundamentals, headlines, code, corp_code)
            if is_negative:
                logging.info(f"[{code}] {name}: 악재성 요인으로 제외됨 - {reason}")
                return None
            
        # 6. 기술적 조건 및 패턴 분석
        analysis_results = check_ma_conditions(df, periods, analyze_patterns) 
        
        # 7. 필터 유형에 따른 최종 매칭 검사
        is_match = True
        if pattern_type_filter:
            if pattern_type_filter == 'goldencross': 
                is_match = analysis_results.get("goldencross_50_200_detected", False)
            elif pattern_type_filter in ['double_bottom', 'triple_bottom', 'cup_and_handle']: 
                status_key = f'pattern_{pattern_type_filter}_status'
                status = analysis_results.get(status_key)
                # 돌파 또는 잠재적 패턴 모두 매칭으로 간주
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
                 # MA 필터가 명시적으로 요청되었을 경우, 모든 MA 조건이 충족되어야 함
                 is_match = all(analysis_results.get(f"above_ma{p}", False) for p in periods)
            else: 
                is_match = False # 알 수 없는 필터 유형
        
        # 필터링 조건에 맞지 않으면 제외
        if pattern_type_filter and not is_match: return None
        
        # 8. 결과 정리 및 반환
        if analysis_results or fundamentals or headlines:
            # None 또는 NaN 값 정리 (JSON 직렬화 오류 방지)
            fundamentals_clean = {k: v for k, v in fundamentals.items() if v is not None and not (isinstance(v, (float, np.float64)) and np.isnan(v))}
            analysis_clean = {k: v for k, v in analysis_results.items() if v is not None and not (isinstance(v, (float, np.float64)) and np.isnan(v))}
            
            return {
                "ticker": code,
                "name": name,
                "technical_conditions": analysis_clean,
                "fundamentals": fundamentals_clean,
                "recent_news_headlines": headlines
            }
        return None
    except Exception as e:
        # 에러 발생 시 로그 기록 후 None 반환 (병렬 처리의 안정성 확보)
        logging.error(f"[ERROR] {code} {name} 분석 실패: {e}\n{traceback.format_exc()}") 
        return None

def run_analysis(workers, ma_periods_str, analyze_patterns, exclude_negatives, pattern_type_filter):
    """병렬 처리를 이용해 전체 종목 분석을 실행하고 진행률을 출력합니다."""
    start_time = time.time()
    periods = [int(p.strip()) for p in ma_periods_str.split(',') if p.strip().isdigit()]
    
    # 패턴 필터가 설정되면 패턴 분석을 강제로 활성화합니다.
    if pattern_type_filter and pattern_type_filter not in ['ma', 'regime'] and not pattern_type_filter.startswith('regime:'): 
        analyze_patterns = True 
    
    # MA 크로스 체크 및 패턴 분석을 위해 50일, 200일은 강제로 포함
    if 50 not in periods: periods.append(50) 
    if 200 not in periods: periods.append(200)

    items = load_listing()
    initial_item_count = len(items)
    results = []
    
    logging.warning(f"분석 시작: 총 {initial_item_count} 종목, 최대 워커 {workers}개 사용. 필터: {pattern_type_filter or 'None'}")

    processed_count = 0
    
    with ThreadPoolExecutor(max_workers=workers) as executor:
        future_to_item = {
            executor.submit(analyze_symbol, item, periods, analyze_patterns, exclude_negatives, pattern_type_filter): item
            for item in items
        }
        
        for future in as_completed(future_to_item):
            # 웹 연동을 위한 실시간 진행률 JSON 출력 (필수)
            processed_count += 1
            progress_percent = round((processed_count / initial_item_count) * 100, 2)
            
            sys.stdout.write(json.dumps({
                "mode": "progress",
                "total_symbols": initial_item_count,
                "processed_symbols": processed_count,
                "progress_percent": progress_percent
            }, ensure_ascii=False) + "\n")
            sys.stdout.flush()
            
            try:
                r = future.result()
                if r: results.append(r)
            except Exception as e:
                code = future_to_item[future].get("Code") or future_to_item[future].get("code")
                name = future_to_item[future].get("Name") or future_to_item[future].get("name")
                logging.error(f"[ERROR] {code} {name} 처리 중 예외 발생: {e}")
    
    end_time = time.time()
    
    data_check = {
        "listing_file_exists": LISTING_FILE.exists(),
        "dart_available": DART_AVAILABLE,
        "total_symbols_loaded": initial_item_count,
        "time_taken_sec": round(end_time - start_time, 2),
    }

    logging.warning(f"분석 완료: {len(results)}개 종목 필터링 됨. 총 소요 시간: {data_check['time_taken_sec']}초")
    # 최종 결과 출력 및 정상 종료 (status_code=0)
    safe_print_json({
        "results": results, 
        "mode": "analyze_result",
        "filter": pattern_type_filter or 'ma_only',
        "data_check": data_check
    }, status_code=0)

def generate_chart(symbol, ma_periods_str):
    """단일 종목의 차트를 생성하고 Base64로 인코딩된 이미지 데이터를 반환합니다."""
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
            
        # 최근 250일 데이터만 사용하여 차트 생성
        df_for_chart = df.iloc[-250:].copy() 
        df_for_chart = calculate_advanced_features(df_for_chart)
        
        if df_for_chart.empty:
            safe_print_json({"error": "특징 계산 후 데이터가 부족하여 차트 생성 불가."}, status_code=1)
            return

        ma_lines = []
        for p in periods:
            ma_col_name = f'SMA_{p}' if p in [20, 50, 200] else f'ma{p}'
            # 이미 계산된 SMA는 재사용하거나, 명시된 기간이 아니면 rolling mean 계산
            if ma_col_name not in df_for_chart.columns:
                df_for_chart[ma_col_name] = df_for_chart['Close'].rolling(window=p, min_periods=1).mean()

            if ma_col_name in df_for_chart.columns and not df_for_chart[ma_col_name].isnull().all():
                color_map = {5: 'red', 20: 'orange', 50: 'purple', 200: 'blue'}
                ma_lines.append(mpf.make_addplot(df_for_chart[ma_col_name], panel=0, type='line', width=1.0, 
                                                 color=color_map.get(p, 'green'), secondary_y=False))
        
        # MACD를 별도 패널에 추가 (panel=2)
        macd_plot = mpf.make_addplot(df_for_chart['MACD'], panel=2, type='line', secondary_y=False, color='red', width=1.0, title='MACD')
        
        # mpf.make_marketcolors 인자 개선: Deprecation 경고를 피하고 최신 규격 따름
        mc = mpf.make_marketcolors(up='red', down='blue', 
                                   edge='black', 
                                   wick='black', 
                                   volume='gray', 
                                   ohlc='i') # ohlc='i'는 Inverted (색상이 채워짐)
        
        s = mpf.make_mpf_style(marketcolors=mc, gridcolor='gray', figcolor='white', y_on_right=False, 
                               rc={'font.family': MPLFINANCE_FONT}, 
                               base_mpf_style='yahoo') # 기본 스타일을 yahoo로 설정하여 안정성 확보
        
        addplots = ma_lines + [macd_plot]
        
        # 차트 생성
        fig, axes = mpf.plot(df_for_chart, type='candle', style=s, 
                             title=f"{name} ({code}) Technical Analysis Chart", 
                             ylabel='Price (KRW)', ylabel_lower='Volume', volume=True, 
                             addplot=addplots, figscale=1.5, returnfig=True, 
                             tight_layout=True)
        
        # 차트를 Base64로 인코딩
        buf = io.BytesIO()
        fig.savefig(buf, format='png', bbox_inches='tight')
        plt.close(fig) # 메모리 해제
        image_base64 = base64.b64encode(buf.getvalue()).decode('utf-8')
        
        # 차트 결과 출력 및 정상 종료 (status_code=0)
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
    parser.add_argument("--symbol", type=str, help="차트 모드에서 사용할 종목 코드")
    parser.add_argument("--analyze_patterns", action="store_true", help="패턴 감지 활성화")
    parser.add_argument("--pattern_type", type=str, 
                        choices=['ma', 'double_bottom', 'triple_bottom', 'cup_and_handle', 'goldencross', 'regime:0', 'regime:1', 'regime:2', 'regime:3'], 
                        help="필터링할 패턴 종류 (예: 'regime:0' 또는 'goldencross')") 
    parser.add_argument("--exclude_negatives", action="store_true", help="악재성 종목 제외")
    args = parser.parse_args()
    
    try:
        if args.mode == 'analyze':
            run_analysis(args.workers, args.ma_periods, args.analyze_patterns, args.exclude_negatives, args.pattern_type) 
        elif args.mode == 'chart':
            if not args.symbol: 
                # 인수 누락 시 치명적 오류 처리 및 즉시 종료
                safe_print_json({
                    "error": "CRITICAL_ERROR", 
                    "reason": "차트 모드에는 --symbol 인수가 필수입니다.",
                    "mode": "argument_check"
                })
            generate_chart(args.symbol, args.ma_periods) 
    except Exception as e:
        error_msg = f"스크립트 실행 중 치명적인 오류 발생: {e}"
        # 기타 예측 불가능한 치명적 오류 발생 시 즉시 종료
        safe_print_json({
            "error": "CRITICAL_ERROR", 
            "reason": error_msg,
            "traceback": traceback.format_exc(),
            "mode": "runtime_error"
        })
    # 모든 정상 실행 경로는 run_analysis 또는 generate_chart 내부의 safe_print_json(status_code=0)에서 처리됨
    sys.exit(0)

if __file__ != '<stdin>' and __name__ == "__main__":
    main()