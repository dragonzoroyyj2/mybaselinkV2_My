import os
import sys
import json
import time
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from datetime import datetime

import FinanceDataReader as fdr
import pandas as pd

# ===============================================
# 경로 설정 (모든 산출물은 python 하위)
# ===============================================
ROOT_DIR = Path(__file__).resolve().parents[2]  # .../python
LOG_DIR = ROOT_DIR / "log"
DATA_DIR = ROOT_DIR / "stock_data"
LISTING_FILE = ROOT_DIR / "stock" / "stock_list" / "stock_listing.json"

LOG_DIR.mkdir(parents=True, exist_ok=True)
DATA_DIR.mkdir(parents=True, exist_ok=True)
LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)

LOG_FILE = LOG_DIR / "update_stock_listing.log"

# ===============================================
# 로깅 설정
# ===============================================
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding="utf-8"),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

def progress(pct, message):
    # 진행률 메시지
    logger.info(f"[PROGRESS] {float(pct):.1f} {message}")
    sys.stdout.flush()

def log(msg):
    logger.info(f"[LOG] {msg}")
    sys.stdout.flush()

def save_listing_json(df: pd.DataFrame):
    df.to_json(LISTING_FILE, orient="records", force_ascii=False, indent=2)
    log(f"KRX 종목 리스트 저장 완료: {LISTING_FILE}")

def fetch_and_save_stock(symbol: str, name: str, force: bool = False):
    """
    개별 종목 데이터를 parquet로 저장 (캐시 우선)
    """
    file_path = DATA_DIR / f"{symbol}.parquet"
    try:
        if file_path.exists() and not force:
            return f"{symbol} {name} → 캐시 사용"
        df = fdr.DataReader(symbol)
        if df is None or df.empty:
            return f"{symbol} {name} → 데이터 없음"
        df.to_parquet(file_path)
        return f"{symbol} {name} → 저장 완료"
    except Exception as e:
        return f"{symbol} {name} → 실패: {e}"

def main():
    import argparse
    parser = argparse.ArgumentParser(description="KRX 종목 데이터 일괄 업데이트")
    parser.add_argument("--force", action="store_true", help="캐시 무시 (강제 재다운로드)")
    parser.add_argument("--workers", type=int, default=8, help="동시 실행 워커 수")
    args = parser.parse_args()

    start_time = time.time()
    force = args.force
    workers = max(1, args.workers)

    completed = 0
    failed = 0
    total_count = 0

    try:
        progress(2, "환경 점검 중...")
        log(f"실행 시작 (force={force}, workers={workers})")

        # 1️⃣ KRX 목록
        progress(5, "KRX 종목 목록 다운로드 중...")
        krx = fdr.StockListing("KRX")
        if krx is None or krx.empty:
            print(json.dumps({"error": "KRX 데이터 다운로드 실패"}, ensure_ascii=False))
            sys.exit(1)

        symbol_col = "Symbol" if "Symbol" in krx.columns else ("Code" if "Code" in krx.columns else None)
        name_col = "Name" if "Name" in krx.columns else ("회사명" if "회사명" in krx.columns else None)
        if not symbol_col or not name_col:
            print(json.dumps({"error": "KRX 컬럼명을 확인하세요(Symbol/Name or Code/회사명)."}, ensure_ascii=False))
            sys.exit(1)

        save_listing_json(krx)
        symbols = krx[symbol_col].astype(str).tolist()
        names   = krx[name_col].astype(str).tolist()
        total_count = len(symbols)

        progress(20, f"KRX 목록 {total_count}건 로드됨")

        if not force:
            log("캐시 우선 모드: 기존 파일이 있으면 재활용")
            progress(25, "캐시 확인 중...")

        # 2️⃣ 개별 종목
        progress(30, "개별 종목 데이터 다운로드 시작")
        update_step = max(1, total_count // 50)

        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {executor.submit(fetch_and_save_stock, sym, nm, force): (idx, sym, nm)
                       for idx, (sym, nm) in enumerate(zip(symbols, names))}
            for future in as_completed(futures):
                idx, sym, nm = futures[future]
                try:
                    result = future.result()
                    completed += 1
                    if "실패" in result:
                        failed += 1
                    log(f"{result} ({completed}/{total_count})")
                    if (completed % update_step == 0) or (completed == total_count):
                        pct = 30.0 + (completed / total_count) * 70.0
                        progress(pct, f"종목 저장 {completed}/{total_count}")
                except Exception as e:
                    failed += 1
                    log(f"{sym} {nm} → 예외 발생: {e}")
                    continue

    except KeyboardInterrupt:
        log("사용자 취소 감지")
        print(json.dumps({"error": "사용자 취소됨"}, ensure_ascii=False))
        sys.exit(2)
    except Exception as e:
        logger.exception(f"예외 발생: {e}")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)

    finally:
        # ✅ 어떤 경우에도 무조건 실행됨
        elapsed = time.time() - start_time
        log(f"총 소요 시간: {elapsed:.2f}초")
        progress(100, "✅ 전체 완료")
        log("✅ 업데이트 완료")

        print(json.dumps({
            "status": "completed",
            "success": completed - failed,
            "failed": failed,
            "total": total_count
        }, ensure_ascii=False))


if __name__ == "__main__":
    # 파이썬 표준출력 무버퍼를 강제하려면 -u 옵션과 함께 호출되며,
    # 여기서는 StreamHandler + flush 로 충분.
    main()
