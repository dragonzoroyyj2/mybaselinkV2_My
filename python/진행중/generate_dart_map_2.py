# generate_dart_map.py
import requests
import zipfile
import io
import json
import xml.etree.ElementTree as ET
from pathlib import Path

# ⭐ 여기에 DART API 키를 넣어주세요.
DART_API_KEY = "599b24c052bb23453a48da3916ae7faf1befd03e" 

# BASE_DIR을 현재 스크립트의 위치로 설정 (경로 오류 방지)
BASE_DIR = Path(__file__).resolve().parent
OUTPUT_FILE = BASE_DIR / "data" / "stock_list" / "dart_corp_codes.json"

def fetch_and_map_dart_codes(api_key):
    """DART API에서 전체 상장법인 코드를 다운로드하고 매핑 파일을 생성합니다."""
    url = f"https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key={api_key}"
    print(f"DART 전체 상장사 Corp Code 다운로드 요청 시작...")
    
    try:
        response = requests.get(url, timeout=30)
        response.raise_for_status() 

        zip_file = zipfile.ZipFile(io.BytesIO(response.content))
        with zip_file.open('CORPCODE.xml') as xml_file:
            xml_data = xml_file.read()

        root = ET.fromstring(xml_data)
        corp_map = {}
        
        for item in root.findall('list'):
            corp_code = item.find('corp_code').text
            stock_code_raw = item.find('stock_code').text
            
            # 주식 코드(Ticker)가 6자리 순수 숫자인 상장사만 필터링
            if stock_code_raw and len(stock_code_raw.strip()) == 6 and stock_code_raw.strip().isdigit():
                ticker = stock_code_raw.strip()
                corp_map[ticker] = corp_code
        
        # 4. JSON 파일 저장 (기존 파일 덮어쓰기)
        OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
            json.dump(corp_map, f, ensure_ascii=False, indent=4)
        
        print(f"✅ DART Corp Code 매핑 파일 생성 완료: {OUTPUT_FILE}")
        print(f"   총 {len(corp_map)}개 종목 매핑 성공.")

    except Exception as e:
        print(f"❌ DART Corp Code 파일 생성 중 오류 발생: {e}")
        print("   DART API 키가 유효한지, 인터넷 연결 상태를 확인해 주세요.")


if __name__ == "__main__":
    # DART API 키가 비어있는 경우만 체크하고, 키가 있으면 바로 실행합니다.
    if not DART_API_KEY or DART_API_KEY == "YOUR_DART_API_KEY_HERE":
        print("❌ DART API 키를 generate_dart_map.py 파일의 DART_API_KEY 변수에 넣어주세요.")
    else:
        fetch_and_map_dart_codes(DART_API_KEY)