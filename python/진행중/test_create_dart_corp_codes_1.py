import pandas as pd
import json
from pathlib import Path

# --- 설정 (이 부분만 고객님의 상황에 맞게 수정해주세요!) ---
# 1. DART에서 다운로드한 3개 파일의 경로 리스트 
# 파일명을 정확히 입력하고, 다운로드 폴더에 이 파일들이 있는지 확인해 주세요.
DART_FILE_PATHS = [
    Path("D:/Test_scheduler_py/dart_kospi.xlsx"), 
    Path("D:/Test_scheduler_py/dart_kosdaq.xlsx"),
    Path("D:/Test_scheduler_py/dart_konex.xlsx"),
] 

# 2. 결과 JSON 파일이 저장될 최종 경로 (경로 오류를 수정해야 합니다!)
JSON_OUTPUT_PATH = Path("D:/Test_scheduler_py/data/stock_list/dart_corp_codes.json")

# 3. 파일 내의 필수 컬럼 이름
TICKER_COLUMN_NAME = '종목코드'  # 6자리 종목 코드
CORP_CODE_COLUMN_NAME = '고유번호' # ⚠️ DART '기업 고유 번호'가 적힌 컬럼의 실제 이름으로 수정하세요!
# ----------------------------------------------------

def generate_json_map():
    """DART 엑셀 파일들을 읽어 Ticker-Corp Code 매핑 JSON을 생성합니다."""
    
    # 최종 매핑 정보를 담을 딕셔너리
    dart_map = {}
    total_processed_files = 0

    for file_path in DART_FILE_PATHS:
        if not file_path.exists():
            print(f"⚠️ 경고: 파일이 존재하지 않습니다. 건너뜁니다: {file_path}")
            continue
        
        try:
            # 엑셀 파일 로드 (openpyxl 라이브러리가 필요할 수 있습니다.)
            df = pd.read_excel(file_path)
            
            # 필수 컬럼 확인
            if TICKER_COLUMN_NAME not in df.columns or CORP_CODE_COLUMN_NAME not in df.columns:
                print(f"❌ 오류: 파일 '{file_path.name}'에 필요한 컬럼이 없습니다.")
                print(f"필요한 컬럼: '{TICKER_COLUMN_NAME}', '{CORP_CODE_COLUMN_NAME}'")
                continue

            # 종목코드가 6자리 숫자인 상장사만 필터링하고 필요한 컬럼 추출
            df_filtered = df[[TICKER_COLUMN_NAME, CORP_CODE_COLUMN_NAME]].copy()
            df_filtered = df_filtered[df_filtered[TICKER_COLUMN_NAME].astype(str).str.match(r'^\d{6}$', na=False)]
            
            # Corp Code를 8자리 문자로 통일 (DART 요구 형식)
            df_filtered[CORP_CODE_COLUMN_NAME] = df_filtered[CORP_CODE_COLUMN_NAME].astype(str).str.zfill(8)
            
            # 딕셔너리에 추가 (중복되는 종목은 나중에 처리된 것으로 덮어씀)
            current_map = df_filtered.set_index(TICKER_COLUMN_NAME)[CORP_CODE_COLUMN_NAME].to_dict()
            dart_map.update(current_map)
            total_processed_files += 1
            print(f"✅ 파일 처리 완료: {file_path.name} (추가된 종목: {len(current_map)}개)")
            
        except Exception as e:
            print(f"❌ 파일 '{file_path.name}' 처리 중 오류 발생: {e}")

    if not dart_map:
        print("\n⛔ 모든 파일을 처리했으나, 유효한 매핑 정보가 생성되지 않았습니다. 컬럼 이름 및 파일 경로를 확인해주세요.")
        return

    # JSON 파일 저장
    try:
        JSON_OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(JSON_OUTPUT_PATH, 'w', encoding='utf-8') as f:
            # 딕셔너리를 JSON 형식으로 저장 (줄바꿈 없이)
            json.dump(dart_map, f, ensure_ascii=False, separators=(',', ':'))
            
        print(f"\n🎉 최종 DART 매핑 파일 생성 완료!")
        print(f"파일 경로: {JSON_OUTPUT_PATH}")
        print(f"총 {total_processed_files}개 파일을 통합하여 {len(dart_map)}개 종목의 매핑 정보를 저장했습니다.")
        
    except Exception as e:
        print(f"\n❌ JSON 파일 저장 중 오류 발생: {e}")

if __name__ == "__main__":
    generate_json_map()