import os
import sys
import json
import time
import logging
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from datetime import datetime

import FinanceDataReader as fdr
import pandas as pd

# ============================================================
# 1️⃣ 경로 설정
# ============================================================
# 스크립트 실행 위치를 루트 디렉터리로 설정합니다.
# 이 방식은 스크립트가 어느 위치에서 실행되든 상대 경로를 올바르게 계산합니다.
ROOT_DIR = Path.cwd()
LOG_DIR = ROOT_DIR / "log"
DATA_DIR = ROOT_DIR / "stock_data"
LISTING_FILE = ROOT_DIR / "stock" / "stock_list" / "stock_listing.json"
LOG_FILE = LOG_DIR / "update_stock_listing.log"

def setup_env():
    """
    환경 설정 함수: 필요한 디렉터리를 생성하고 로깅 시스템을 설정합니다.
    """
    # 로그 파일을 저장할 디렉터리 생성 (이미 존재하면 무시)
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    # 개별 종목 데이터를 저장할 디렉터리 생성 (이미 존재하면 무시)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    # KRX 종목 목록 파일을 저장할 디렉터리 생성 (이미 존재하면 무시)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)

    # 로깅 설정:
    # - INFO 레벨 이상의 로그를 기록합니다.
    # - 로그 포맷을 지정하여 시간, 레벨, 메시지를 포함합니다.
    # - FileHandler를 사용하여 로그를 파일에 저장합니다 (인코딩: UTF-8).
    # - StreamHandler를 사용하여 로그를 콘솔(표준 출력)에도 표시합니다.
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[
            logging.FileHandler(LOG_FILE, encoding="utf-8"),
            logging.StreamHandler(sys.stdout)
        ]
    )

# ============================================================
# 2️⃣ KRX 종목 목록 처리
# ============================================================
def download_and_save_listing():
    """
    KRX 종목 목록을 FinanceDataReader를 이용해 다운로드하고, 
    정제된 데이터를 JSON 파일로 저장합니다.
    
    Returns:
        pandas.DataFrame: 다운로드 및 처리된 KRX 종목 목록 데이터프레임.
    
    Raises:
        ValueError: KRX 데이터를 다운로드하는 데 실패했을 경우 발생.
    """
    logging.info("[PROGRESS] 5.0 KRX 종목 목록 다운로드 중...")
    krx = fdr.StockListing("KRX")

    if krx is None or krx.empty:
        raise ValueError("KRX 데이터 다운로드 실패: FinanceDataReader에서 데이터를 가져오지 못했습니다.")

    # 컬럼 표준화: FinanceDataReader 버전이나 데이터 소스에 따라 컬럼명이 달라질 수 있으므로,
    # 예상되는 컬럼이 없으면 None으로 채워 데이터 구조를 통일합니다.
    expected_columns = [
        "Code", "ISU_CD", "Name", "Market", "Dept",
        "Close", "ChangeCode", "Changes", "ChagesRatio",
        "Open", "High", "Low", "Volume", "Amount",
        "Marcap", "Stocks", "MarketId"
    ]
    for col in expected_columns:
        if col not in krx.columns:
            krx[col] = None

    # 시스템 데이터 수집 날짜를 'Date' 컬럼으로 추가합니다.
    krx["Date"] = datetime.now().strftime("%Y-%m-%d")
    
    # 데이터프레임을 JSON 파일로 저장합니다.
    # orient="records": 레코드 리스트 형태로 저장
    # force_ascii=False: 한글을 유니코드로 변환하지 않고 그대로 저장
    # indent=2: 가독성을 위해 들여쓰기를 2칸으로 설정
    krx.to_json(LISTING_FILE, orient="records", force_ascii=False, indent=2)
    logging.info(f"[LOG] KRX 종목 리스트 저장 완료: {LISTING_FILE}")

    return krx

# ============================================================
# 3️⃣ 개별 종목 데이터 처리
# ============================================================
def fetch_and_save_stock(symbol: str, name: str, force: bool = False):
    """
    개별 종목 데이터를 FinanceDataReader로 조회하고 Parquet 파일로 저장합니다.
    
    Args:
        symbol (str): 종목 코드.
        name (str): 종목명.
        force (bool, optional): 캐시 사용 여부를 결정하는 플래그. True면 캐시를 무시합니다.
    
    Returns:
        tuple: (결과 메시지 문자열, 결과 타입 문자열)
    """
    file_path = DATA_DIR / f"{symbol}.parquet"
    
    # 캐싱 로직: 파일이 이미 존재하고 force 플래그가 False일 경우 캐시를 사용합니다.
    if file_path.exists() and not force:
        return f"{symbol} {name} → 캐시 사용", "cached"

    try:
        # FinanceDataReader를 사용하여 종목 데이터 조회
        df = fdr.DataReader(symbol)
        if df is None or df.empty:
            return f"{symbol} {name} → 데이터 없음", "no_data"

        # 데이터프레임을 Parquet 파일로 저장합니다.
        df.to_parquet(file_path)
        return f"{symbol} {name} → 저장 완료", "success"
    except Exception as e:
        # 데이터 조회 또는 저장 중 예외 발생 시 로그 기록
        logging.error(f"예외 발생: {symbol} {name} → {e}")
        return f"{symbol} {name} → 실패: {e}", "failed"

def download_and_save_stocks(krx: pd.DataFrame, workers: int, force: bool):
    """
    병렬 처리를 통해 KRX 종목 목록에 있는 모든 개별 종목 데이터를 다운로드하고 저장합니다.
    
    Args:
        krx (pd.DataFrame): KRX 종목 목록 데이터프레임.
        workers (int): 동시에 실행할 워커(스레드)의 수.
        force (bool): 캐시 무시 여부 플래그.
        
    Returns:
        tuple: (성공적으로 완료된 수, 실패한 수, 총 종목 수)
    """
    symbols = krx["Code"].astype(str).tolist()
    names = krx["Name"].astype(str).tolist()
    total_count = len(symbols)

    logging.info(f"[PROGRESS] 20.0 KRX 목록 {total_count}건 로드됨")
    if not force:
        logging.info("[LOG] 캐시 우선 모드: 기존 파일 재활용")
        logging.info("[PROGRESS] 25.0 캐시 확인 중...")

    logging.info("[PROGRESS] 30.0 개별 종목 데이터 다운로드 시작")
    
    # 진행률 업데이트 빈도를 계산합니다. 총 50단계로 진행률을 표시합니다.
    update_step = max(1, total_count // 50)
    completed_count = 0
    failed_count = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        # 종목별로 fetch_and_save_stock 함수를 병렬 실행하도록 예약합니다.
        futures = {
            executor.submit(fetch_and_save_stock, sym, nm, force): (idx, sym, nm)
            for idx, (sym, nm) in enumerate(zip(symbols, names))
        }

        # 완료된 작업부터 결과를 처리합니다.
        for future in as_completed(futures):
            idx, sym, nm = futures[future]
            try:
                result_msg, result_type = future.result()
                
                # 결과 타입에 따라 성공/실패 카운트를 업데이트합니다.
                if result_type == "failed":
                    failed_count += 1
                completed_count += 1
                
                logging.info(f"[LOG] {result_msg} ({completed_count}/{total_count})")

                # 일정 단계마다 진행률을 로그로 표시합니다.
                if (completed_count % update_step == 0) or (completed_count == total_count):
                    pct = 30.0 + (completed_count / total_count) * 70.0
                    logging.info(f"[PROGRESS] {pct:.1f} 종목 저장 {completed_count}/{total_count}")
            except Exception as e:
                # 스레드 내부에서 발생한 예외를 처리합니다.
                failed_count += 1
                logging.error(f"예외 발생: {sym} {nm} → {e}")

    return completed_count, failed_count, total_count

# ============================================================
# 4️⃣ 메인 함수
# ============================================================
def main():
    """
    스크립트의 주 실행 진입점.
    인수 파싱, 환경 설정, 데이터 다운로드 및 저장, 결과 출력을 담당합니다.
    """
    # 명령줄 인수 파서 설정
    parser = argparse.ArgumentParser(description="KRX 종목 데이터 일괄 업데이트")
    parser.add_argument("--force", action="store_true", help="캐시 무시 (강제 재다운로드)")
    parser.add_argument("--workers", type=int, default=8, help="동시 실행 워커 수")
    args = parser.parse_args()

    start_time = time.time()
    setup_env()
    
    logging.info("[PROGRESS] 2.0 환경 점검 중...")
    logging.info(f"[LOG] 실행 시작 (force={args.force}, workers={args.workers})")

    try:
        # KRX 목록 다운로드 및 저장
        krx_listing = download_and_save_listing()
        # 개별 종목 데이터 다운로드 및 저장 (병렬 처리)
        completed, failed, total = download_and_save_stocks(krx_listing, args.workers, args.force)
    except KeyboardInterrupt:
        # 사용자가 Ctrl+C로 취소했을 때 처리
        logging.info("[LOG] 사용자 취소 감지")
        print(json.dumps({"error": "사용자 취소됨"}, ensure_ascii=False))
        sys.exit(2)
    except Exception as e:
        # 기타 예상치 못한 예외 처리
        logging.exception("메인 함수 실행 중 예외 발생")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)

    finally:
        # 실행 종료 후 시간 및 최종 상태 기록
        elapsed = time.time() - start_time
        logging.info(f"[LOG] 총 소요 시간: {elapsed:.2f}초")
        logging.info("[PROGRESS] 100.0 전체 완료")
        logging.info("[LOG] 업데이트 완료")

        # 최종 결과를 JSON 형식으로 출력
        print(json.dumps({
            "status": "completed",
            "success": completed - failed,
            "failed": failed,
            "total": total
        }, ensure_ascii=False))

# ============================================================
# 실행 진입점
# ============================================================
if __name__ == "__main__":
    main()