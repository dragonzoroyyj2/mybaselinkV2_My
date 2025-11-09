# -*- coding: utf-8 -*-
"""
📘 update_stock_listing_prod.py (v2.6 실전 안정판)
----------------------------------------------------------
✅ StockBatchGProdService(v3.3) 완전 동기화
✅ BASE_DIR = Path(__file__).resolve().parents[2]
✅ Spring @Value("${opendart.dart_api_key:}") → --dart_api_key 인자 완전 대응
✅ DART_API_KEY 인자 전달 시 환경변수 자동 설정
✅ 로직/구조/진행률/로깅 기존 완전 유지
----------------------------------------------------------
"""

import os
import sys
import json
import time
import logging
import argparse
import socket
from pathlib import Path
from datetime import datetime, timedelta
from typing import Dict, Any
import pandas as pd
from concurrent.futures import ThreadPoolExecutor, as_completed, TimeoutError

try:
    import FinanceDataReader as fdr
    import requests
except ModuleNotFoundError as e:
    print(json.dumps({"error": f"필수 모듈 누락: {e.name} 설치 필요"}, ensure_ascii=False), flush=True)
    sys.exit(1)

# ==============================
# 상수 정의
# ==============================
PER_STOCK_TIMEOUT = 10
MAX_RETRIES = 3
KRX_LIST_CACHE_DAYS = 1

DEFAULT_WORKERS = 16
DEFAULT_HISTORY_YEARS = 3

# ==============================
# 경로 설정
# ==============================
BASE_DIR = Path(__file__).resolve().parents[2]
LOG_DIR = BASE_DIR / "log"
DATA_DIR = BASE_DIR / "data" / "stock_data"
LISTING_FILE = BASE_DIR / "data" / "stock_list" / "stock_listing.json"
LOG_FILE = LOG_DIR / "robust_stock_updater.log"

# ==============================
# 로깅 초기화
# ==============================
def setup_env():
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    LISTING_FILE.parent.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
        handlers=[
            logging.FileHandler(LOG_FILE, encoding="utf-8"),
            logging.StreamHandler(sys.stdout)
        ]
    )
setup_env()

# ==============================
# 네트워크 확인
# ==============================
def check_network_connection(host="www.google.com", port=80, timeout=5):
    try:
        socket.setdefaulttimeout(timeout)
        socket.socket(socket.AF_INET, socket.SOCK_STREAM).connect((host, port))
        logging.info("네트워크 연결 성공.")
        return True
    except Exception as e:
        msg = f"네트워크 연결 실패: {e}"
        logging.critical(msg)
        print(json.dumps({"error": msg}, ensure_ascii=False), flush=True)
        sys.exit(1)

# ==============================
# KRX 목록 로드
# ==============================
def load_krx_listing():
    logging.info("[PROGRESS] 5.0 KRX 종목 목록 로드 중...")
    total = 0
    if LISTING_FILE.exists():
        try:
            krx = pd.read_json(LISTING_FILE, orient="records")
            if not krx.empty:
                total = len(krx)
                logging.info(f"[LOG] KRX 종목 목록 캐시 로드 ({total}개)")
                logging.info(f"[KRX_TOTAL] {total}")
                logging.info(f"[KRX_SAVED] {total}")
                logging.info("[PROGRESS] 10.0 KRX 목록 로드 완료 (캐시)")
                return krx
        except Exception:
            logging.warning("[LOG] KRX 캐시 로드 실패, 재다운로드 시도")

    try:
        krx = fdr.StockListing("KRX")
        if krx is None or krx.empty:
            raise ValueError("KRX 데이터 다운로드 실패")
        krx.rename(columns={'Symbol': 'Code'}, inplace=True)
        krx["Date"] = datetime.now().strftime("%Y-%m-%d")
        krx.to_json(LISTING_FILE, orient="records", force_ascii=False, indent=2)
        total = len(krx)
        logging.info(f"[LOG] KRX 종목 리스트 저장 완료 ({total}개)")
        logging.info(f"[KRX_TOTAL] {total}")
        logging.info(f"[KRX_SAVED] {total}")
        logging.info("[PROGRESS] 10.0 KRX 목록 로드 완료 (재다운로드)")
        return krx
    except Exception as e:
        logging.error(f"[ERROR] KRX 목록 다운로드 실패: {e}")
        print(json.dumps({"status": "failed", "error": str(e)}), flush=True)
        sys.exit(1)

# ==============================
# 단일 종목 데이터 수집
# ==============================
def fetch_and_save_data(item: Dict[str, Any], history_years: int, force_download: bool):
    code = item.get("Code")
    name = item.get("Name")
    path = DATA_DIR / f"{code}.parquet"
    end_date = datetime.now().date()
    existing_df = pd.DataFrame()
    last_date = None

    if path.exists() and not force_download:
        try:
            existing_df = pd.read_parquet(path)
            if not existing_df.empty and 'Date' in existing_df.columns:
                existing_df['Date'] = pd.to_datetime(existing_df['Date'])
                last_date = existing_df['Date'].max().date()
                if last_date >= end_date:
                    return f"{code} {name} → 이미 최신", "cached"
        except Exception:
            last_date = None

    if last_date and not force_download:
        start_date_str = (last_date + timedelta(days=1)).strftime('%Y-%m-%d')
        update_type = "증분"
    else:
        start_date_str = (datetime.now() - timedelta(days=history_years * 365)).strftime('%Y-%m-%d')
        update_type = "전체"

    for attempt in range(MAX_RETRIES):
        try:
            df = fdr.DataReader(code, start=start_date_str, end=end_date.strftime('%Y-%m-%d'))
            if df.empty:
                return f"{code} {name} → 데이터 없음", "no_data"
            df = df.reset_index()
            if update_type == "증분" and not existing_df.empty:
                existing_df['Date'] = pd.to_datetime(existing_df['Date'])
                combined_df = pd.concat([existing_df, df], ignore_index=True).drop_duplicates(subset=['Date'], keep='last')
                combined_df.sort_values(by='Date').to_parquet(path, index=False)
                return f"{code} {name} → 저장 완료 (증분, {len(df)}행)", "success"
            else:
                df.to_parquet(path, index=False)
                return f"{code} {name} → 저장 완료 ({update_type}, {len(df)}행)", "success"
        except requests.exceptions.RequestException as e:
            if attempt < MAX_RETRIES - 1:
                time.sleep(1 + attempt)
            else:
                return f"{code} {name} → 실패: {type(e).__name__}", "failed"
        except Exception as e:
            return f"{code} {name} → 실패: {type(e).__name__}", "failed"

    return f"{code} {name} → 최종 실패", "failed"

# ==============================
# 병렬 다운로드 처리
# ==============================
def download_and_save_stocks(krx, workers: int, history_years: int, force_download: bool):
    items = krx.to_dict('records')
    total_count = len(items)
    logging.info(f"[PROGRESS] 25.0 KRX 목록 {total_count}건 로드됨 (워커: {workers})")
    if force_download:
        logging.info("[LOG] --force 전체 다운로드 강제모드")
    logging.info("[PROGRESS] 30.0 개별 종목 다운로드 시작")

    update_step = max(1, total_count // 50)
    completed_count = success_count = failed_count = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(fetch_and_save_data, item, history_years, force_download): item for item in items}

        for future in as_completed(futures):
            item = futures[future]
            code = item.get("Code")
            try:
                result_msg, result_type = future.result(timeout=PER_STOCK_TIMEOUT)
                if result_type == "failed":
                    failed_count += 1
                elif result_type != "cached":
                    success_count += 1
                completed_count += 1
                logging.info(f"[LOG] {result_msg} ({completed_count}/{total_count})")
                if (completed_count % update_step == 0) or (completed_count == total_count):
                    pct = 30.0 + (completed_count / total_count) * 70.0
                    logging.info(f"[PROGRESS] {pct:.1f} 종목 저장 {completed_count}/{total_count}")
            except TimeoutError:
                failed_count += 1
                completed_count += 1
                logging.error(f"[TIMEOUT] {code} → 응답 없음 ({PER_STOCK_TIMEOUT}초 초과)")
            except Exception as e:
                failed_count += 1
                completed_count += 1
                logging.error(f"[ERROR] {code} 예외 발생: {e}")

    progress = 30.0 + (completed_count / total_count) * 70.0 if total_count > 0 else 0.0
    return completed_count, success_count, failed_count, total_count, progress

# ==============================
# 메인 실행
# ==============================
def main():
    parser = argparse.ArgumentParser(description="주식 시세 데이터 업데이트 (v2.6)")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--history_years", type=int, default=DEFAULT_HISTORY_YEARS)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--dart_api_key", type=str, default=None)
    args = parser.parse_args()

    # ✅ Spring에서 넘어온 DART 키를 환경 변수에 반영
    if args.dart_api_key:
        os.environ["DART_API_KEY"] = args.dart_api_key
        logging.info(f"[LOG] DART_API_KEY 인자 수신 및 환경 변수 설정 완료")

    start_time = time.time()
    stats = {"status": "failed", "success": 0, "failed": 0, "total": 0, "progress": 0.0}
    check_network_connection()

    try:
        krx_listing = load_krx_listing()
        completed, success, failed, total, progress = download_and_save_stocks(krx_listing, args.workers, args.history_years, args.force)
        stats.update({"success": success, "failed": failed, "total": total, "progress": round(progress, 1)})

        # ✅ 일부 실패는 정상 종료
        if completed == total:
            stats["status"] = "completed"
        else:
            stats["status"] = "failed"

    except Exception as e:
        logging.critical(f"[ERROR] 실행 중 오류: {e}", exc_info=True)
        stats.update({"status": "failed", "error": str(e)})

    finally:
        elapsed = time.time() - start_time
        logging.info(f"[LOG] 총 소요: {elapsed:.2f}초")

        if stats["status"] == "completed":
            stats["progress"] = 100.0
        logging.info(f"[PROGRESS] {stats['progress']:.1f} 최종 진행률 반영")

        print(json.dumps(stats, ensure_ascii=False), flush=True)
        logging.shutdown()

        sys.exit(0 if stats["status"] == "completed" else 1)


if __name__ == "__main__":
    main()
