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

    # 컬럼 표준화
    expected_columns = [
        "Code", "ISU_CD", "Name", "Market", "Dept",
        "Close", "ChangeCode", "Changes", "ChagesRatio",
        "Open", "High", "Low", "Volume", "Amount",
        "Marcap", "Stocks", "MarketId"
    ]
    for col in expected_columns:
        if col not in krx.columns:
            krx[col] = None

    # 수집 날짜
    krx["Date"] = datetime.now().strftime("%Y-%m-%d")
    
    # JSON 저장
    krx.to_json(LISTING_FILE, orient="records", force_ascii=False, indent=2)
    logging.info(f"[LOG] KRX 종목 리스트 저장 완료: {LISTING_FILE}")

    # ✅ 추가: KRX 총 개수/저장 개수(=총개수) 로그로 전송 → 서버가 배지 표시에 사용
    total = len(krx)
    logging.info(f"[KRX_TOTAL] {total}")
    logging.info(f"[KRX_SAVED] {total}")

    return krx

# ============================================================
# 3️⃣ 개별 종목 데이터 처리
# ============================================================
def fetch_and_save_stock(symbol: str, name: str, force: bool = False):
    file_path = DATA_DIR / f"{symbol}.parquet"
    if file_path.exists() and not force:
        return f"{symbol} {name} → 캐시 사용", "cached"

    try:
        df = fdr.DataReader(symbol)
        if df is None or df.empty:
            return f"{symbol} {name} → 데이터 없음", "no_data"
        df.to_parquet(file_path)
        return f"{symbol} {name} → 저장 완료", "success"
    except Exception as e:
        logging.error(f"예외 발생: {symbol} {name} → {e}")
        return f"{symbol} {name} → 실패: {e}", "failed"

def download_and_save_stocks(krx: pd.DataFrame, workers: int, force: bool):
    symbols = krx["Code"].astype(str).tolist()
    names = krx["Name"].astype(str).tolist()
    total_count = len(symbols)

    logging.info(f"[PROGRESS] 20.0 KRX 목록 {total_count}건 로드됨")
    if not force:
        logging.info("[LOG] 캐시 우선 모드: 기존 파일 재활용")
        logging.info("[PROGRESS] 25.0 캐시 확인 중...")

    logging.info("[PROGRESS] 30.0 개별 종목 데이터 다운로드 시작")
    
    update_step = max(1, total_count // 50)
    completed_count = 0
    failed_count = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(fetch_and_save_stock, sym, nm, force): (idx, sym, nm)
            for idx, (sym, nm) in enumerate(zip(symbols, names))
        }

        for future in as_completed(futures):
            idx, sym, nm = futures[future]
            try:
                result_msg, result_type = future.result()
                if result_type == "failed":
                    failed_count += 1
                completed_count += 1
                
                logging.info(f"[LOG] {result_msg} ({completed_count}/{total_count})")

                if (completed_count % update_step == 0) or (completed_count == total_count):
                    pct = 30.0 + (completed_count / total_count) * 70.0
                    logging.info(f"[PROGRESS] {pct:.1f} 종목 저장 {completed_count}/{total_count}")
            except Exception as e:
                failed_count += 1
                logging.error(f"예외 발생: {sym} {nm} → {e}")

    return completed_count, failed_count, total_count

# ============================================================
# 4️⃣ 메인 함수
# ============================================================
def main():
    parser = argparse.ArgumentParser(description="KRX 종목 데이터 일괄 업데이트")
    parser.add_argument("--force", action="store_true", help="캐시 무시 (강제 재다운로드)")
    parser.add_argument("--workers", type=int, default=8, help="동시 실행 워커 수")
    args = parser.parse_args()

    start_time = time.time()
    setup_env()
    
    logging.info("[PROGRESS] 2.0 환경 점검 중...")
    logging.info(f"[LOG] 실행 시작 (force={args.force}, workers={args.workers})")

    try:
        krx_listing = download_and_save_listing()
        completed, failed, total = download_and_save_stocks(krx_listing, args.workers, args.force)
    except KeyboardInterrupt:
        logging.info("[LOG] 사용자 취소 감지")
        print(json.dumps({"error": "사용자 취소됨"}, ensure_ascii=False))
        sys.exit(2)
    except Exception as e:
        logging.exception("메인 함수 실행 중 예외 발생")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)
    finally:
        elapsed = time.time() - start_time
        logging.info(f"[LOG] 총 소요 시간: {elapsed:.2f}초")
        logging.info("[PROGRESS] 100.0 전체 완료")
        logging.info("[LOG] 업데이트 완료")
        print(json.dumps({
            "status": "completed",
            "success": completed - failed,
            "failed": failed,
            "total": total
        }, ensure_ascii=False))

if __name__ == "__main__":
    main()
