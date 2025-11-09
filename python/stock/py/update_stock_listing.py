# -*- coding: utf-8 -*-
"""
📘 update_stock_listing.py
--------------------------------------------
KRX 종목 전체 다운로드 + 개별 종목 데이터 저장

✅ 주요 기능:
1. KRX 전체 종목 리스트 다운로드
2. 각 종목별 FinanceDataReader로 일별 데이터 저장 (.parquet)
3. 진행률 로그 출력 (Spring Boot SSE에서 실시간 표시)
4. 네트워크 장애, Python 미설치, 응답지연 모두 안전하게 처리

🧠 실행 방식:
java(Spring Boot) -> 이 파일을 subprocess로 실행
로그는 표준출력(stdout)으로 SSE로 전달됨

⚠️ 절대 print 대신 logging 사용해야 함!
"""

import os
import sys
import json
import time
import logging
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed, TimeoutError
from pathlib import Path
from datetime import datetime

# ==============================
# 필수 라이브러리 확인
# ==============================
try:
    import FinanceDataReader as fdr
    import pandas as pd
except ModuleNotFoundError as e:
    # 🧱 Python 환경에 필요한 라이브러리가 설치되지 않음
    print(json.dumps({"error": f"필수 모듈 누락: {e.name} 설치 필요"}, ensure_ascii=False))
    sys.exit(1)

# ==============================
# 1️⃣ 경로 설정 (절대경로 고정)
# ==============================
# 현재 파일: /MyBaseLinkV2/python/stock/py/update_stock_listing.py
# → 상위 2단계로 올라가면 /MyBaseLinkV2/python
BASE_DIR = Path(__file__).resolve().parents[2]
ROOT_DIR = BASE_DIR  # 유지

LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data"
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json"
LOG_FILE = LOG_DIR / "update_stock_listing.log"

# ==============================
# 2️⃣ 환경 초기화
# ==============================
def setup_env():
    """
    폴더가 없으면 자동 생성하고, 로깅 설정을 초기화한다.
    """
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[
            # 로그를 파일에도 저장
            logging.FileHandler(LOG_FILE, encoding="utf-8"),
            # 콘솔에도 동시에 출력 (Spring Boot SSE에서 수신)
            logging.StreamHandler(sys.stdout)
        ]
    )


# ==============================
# 3️⃣ KRX 종목 목록 다운로드
# ==============================
def download_and_save_listing():
    """
    KRX 전체 종목 목록을 FinanceDataReader로 가져와 JSON으로 저장한다.
    실패 시 예외 발생.
    """
    try:
        logging.info("[PROGRESS] 5.0 KRX 종목 목록 다운로드 중...")
        krx = fdr.StockListing("KRX")

        # 네트워크 문제로 None 리턴 시
        if krx is None or krx.empty:
            raise ValueError("KRX 데이터 다운로드 실패 (네트워크 장애 또는 서버 오류)")

        # 예상 컬럼 없을 경우 기본값 채움
        expected_columns = [
            "Code", "ISU_CD", "Name", "Market", "Dept",
            "Close", "ChangeCode", "Changes", "ChagesRatio",
            "Open", "High", "Low", "Volume", "Amount",
            "Marcap", "Stocks", "MarketId"
        ]
        for col in expected_columns:
            if col not in krx.columns:
                krx[col] = None

        # 날짜 추가
        krx["Date"] = datetime.now().strftime("%Y-%m-%d")

        # JSON 저장
        krx.to_json(LISTING_FILE, orient="records", force_ascii=False, indent=2)
        logging.info(f"[LOG] KRX 종목 리스트 저장 완료: {LISTING_FILE}")

        total = len(krx)
        logging.info(f"[KRX_TOTAL] {total}")
        logging.info(f"[KRX_SAVED] {total}")
        return krx

    except Exception as e:
        logging.error(f"[ERROR] KRX 목록 다운로드 실패: {e}")
        print(json.dumps({"error": "KRX 목록 다운로드 실패", "detail": str(e)}, ensure_ascii=False))
        sys.exit(1)


# ==============================
# 4️⃣ 개별 종목 저장 함수
# ==============================
def fetch_and_save_stock(symbol: str, name: str, force: bool = False):
    """
    각 종목(symbol)의 일별 데이터를 parquet 파일로 저장한다.
    force=False일 경우 이미 존재하면 건너뜀.
    """
    file_path = DATA_DIR / f"{symbol}.parquet"

    # 캐시가 존재하면 생략
    if file_path.exists() and not force:
        return f"{symbol} {name} → 캐시 사용", "cached"

    try:
        df = fdr.DataReader(symbol)

        # 데이터가 없을 경우
        if df is None or df.empty:
            return f"{symbol} {name} → 데이터 없음", "no_data"

        # parquet 저장
        df.to_parquet(file_path)
        return f"{symbol} {name} → 저장 완료", "success"

    except Exception as e:
        logging.error(f"예외 발생: {symbol} {name} → {e}")
        return f"{symbol} {name} → 실패: {e}", "failed"


# ==============================
# 5️⃣ 전체 종목 병렬 다운로드
# ==============================
def download_and_save_stocks(krx, workers: int, force: bool):
    """
    ThreadPoolExecutor로 병렬 다운로드.
    ============================================================
    ✅ 개선된 특징
    ------------------------------------------------------------
    1️⃣ 각 종목 처리 최대 10초 (응답 없으면 즉시 스킵)
    2️⃣ 전체 병렬 다운로드 5분 제한 (남으면 전부 강제 취소)
    3️⃣ 진행률 2% 단위로 [PROGRESS] 로그 출력
    4️⃣ 중간 실패나 네트워크 오류도 전체 중단 없이 계속 진행
    ============================================================
    """
    import concurrent.futures

    symbols = krx["Code"].astype(str).tolist()
    names = krx["Name"].astype(str).tolist()
    total_count = len(symbols)

    logging.info(f"[PROGRESS] 20.0 KRX 목록 {total_count}건 로드됨")
    if not force:
        logging.info("[LOG] 캐시 우선 모드 (기존 파일은 건너뜀)")
        logging.info("[PROGRESS] 25.0 캐시 확인 중...")

    logging.info("[PROGRESS] 30.0 개별 종목 데이터 다운로드 시작")

    update_step = max(1, total_count // 50)  # 약 2% 단위로 로그 표시
    completed_count = 0
    failed_count = 0
    start_time = time.time()

    MAX_TOTAL_SECONDS = 300  # ✅ 전체 5분 제한 (300초)
    PER_STOCK_TIMEOUT = 10   # ✅ 각 종목별 10초 제한

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(fetch_and_save_stock, sym, nm, force): (idx, sym, nm)
            for idx, (sym, nm) in enumerate(zip(symbols, names))
        }

        try:
            for future in concurrent.futures.as_completed(futures, timeout=MAX_TOTAL_SECONDS):
                idx, sym, nm = futures[future]
                try:
                    # ✅ 한 종목당 최대 10초 대기
                    result_msg, result_type = future.result(timeout=PER_STOCK_TIMEOUT)

                    if result_type == "failed":
                        failed_count += 1
                    completed_count += 1

                    logging.info(f"[LOG] {result_msg} ({completed_count}/{total_count})")

                    # 일정 단위마다 진행률 출력
                    if (completed_count % update_step == 0) or (completed_count == total_count):
                        pct = 30.0 + (completed_count / total_count) * 70.0
                        logging.info(f"[PROGRESS] {pct:.1f} 종목 저장 {completed_count}/{total_count}")

                except concurrent.futures.TimeoutError:
                    failed_count += 1
                    logging.error(f"[TIMEOUT] {sym} {nm} → 응답 없음 (10초 초과 스킵)")
                except Exception as e:
                    failed_count += 1
                    logging.error(f"[ERROR] {sym} {nm} → {e}")

        except concurrent.futures.TimeoutError:
            # ✅ 전체 5분 초과 시 아직 안 끝난 작업 강제 취소
            remaining = [f for f in futures if not f.done()]
            for f in remaining:
                f.cancel()
            logging.error(f"[GLOBAL TIMEOUT] 전체 다운로드 제한(5분) 초과 — 남은 {len(remaining)}개 작업 취소")

    return completed_count, failed_count, total_count



# ==============================
# 6️⃣ 메인 함수
# ==============================
def main():
    """
    전체 프로세스 실행 순서:
    1️⃣ 환경 세팅
    2️⃣ KRX 목록 다운로드
    3️⃣ 개별 종목 데이터 병렬 다운로드
    4️⃣ 결과 출력 및 종료
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()

    # 실행 시작 로그
    start_time = time.time()
    setup_env()
    logging.info("[PROGRESS] 2.0 환경 점검 중...")
    logging.info(f"[LOG] 실행 시작 (force={args.force}, workers={args.workers})")

    # ✅ Python 환경 점검 (실행 도중에 인터프리터 깨진 경우)
    if not sys.executable or not os.path.exists(sys.executable):
        logging.error("[ERROR] Python 실행 파일을 찾을 수 없습니다.")
        print(json.dumps({"error": "Python 실행 불가"}, ensure_ascii=False))
        sys.exit(1)

    try:
        krx_listing = download_and_save_listing()
        completed, failed, total = download_and_save_stocks(krx_listing, args.workers, args.force)

    except KeyboardInterrupt:
        # 사용자가 중간에 Ctrl+C 누름
        logging.info("[LOG] 사용자 취소 감지")
        print(json.dumps({"error": "사용자 취소됨"}, ensure_ascii=False))
        sys.exit(2)

    except Exception as e:
        # 예기치 못한 오류
        logging.error(f"[ERROR] 예외 발생: {e}")
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        sys.exit(1)

    finally:
        # 항상 호출되는 종료 처리
        elapsed = time.time() - start_time
        logging.info(f"[LOG] 총 소요: {elapsed:.2f}초")
        logging.info("[PROGRESS] 100.0 전체 완료")
        logging.info("[LOG] 업데이트 완료")

        print(json.dumps({
            "status": "completed",
            "success": completed - failed,
            "failed": failed,
            "total": total
        }, ensure_ascii=False))

        sys.exit(0)


# ==============================
# 7️⃣ 스크립트 시작점
# ==============================
if __name__ == "__main__":
    main()