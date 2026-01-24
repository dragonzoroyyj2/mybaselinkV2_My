# 파일명: news_search_dart_list.py
# -*- coding: utf-8 -*-
import requests
from datetime import datetime, timedelta
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

def get_current_profit_status(api_key, corp_code):
    """
    최신 리포트(3분기 > 반기 > 1분기 > 결산)를 뒤져서
    현재 영업이익이 플러스(+)면 [흑자], 마이너스(-)면 [적자]로 표시
    """
    url = "https://opendart.fss.or.kr/api/fnlttSinglAcnt.json"
    current_year = datetime.now().year
    
    # 2026년 초반이므로 2025년과 2024년 데이터를 순차적으로 탐색
    years = [str(current_year), str(current_year - 1)]
    report_codes = ['11014', '11012', '11013', '11011']
    
    for year in years:
        for r_code in report_codes:
            params = {
                'crtfc_key': api_key,
                'corp_code': corp_code,
                'bsns_year': year,
                'reprt_code': r_code
            }
            try:
                res = requests.get(url, params=params, verify=False, timeout=1.5)
                data = res.json()
                if data.get('status') == '000' and 'list' in data:
                    for item in data.get('list', []):
                        if item['account_nm'] in ['영업이익', '영업손익', '영업이익(손실)']:
                            val_str = item['thstrm_amount'].replace(',', '')
                            if not val_str or val_str == '-': continue
                            
                            val = int(val_str)
                            status = "[흑자]" if val > 0 else "[적자]"
                            r_name = {"11014":"3분기", "11012":"반기", "11013":"1분기", "11011":"결산"}.get(r_code)
                            return f"{status} ({year}년 {r_name}기준)"
            except: continue
    return "[재무미확인]"

def start_dart_system():
    api_key = "599b24c052bb23453a48da3916ae7faf1befd03e"
    
    # ✅ [날짜 자동 보정 로직 추가]
    now = datetime.now()
    # 오전 7시 30분 기준 설정
    base_time = now.replace(hour=7, minute=30, second=0, microsecond=0)
    
    if now < base_time:
        # 07:30 전이면 어제 날짜로 설정
        search_date = (now - timedelta(days=1)).strftime('%Y%m%d')
        print(f"[*] 현재 시간({now.strftime('%H:%M')})이 07:30 이전이므로 어제({search_date}) 데이터를 조회합니다.")
    else:
        # 07:30 후면 오늘 날짜로 설정
        search_date = now.strftime('%Y%m%d')
        print(f"[*] 오늘({search_date}) 공시 데이터를 조회합니다.")

    GOOD_KEYWORDS = [
        "공급계약", "수주", "판매계약", "체결", 
        "흑자전환", "영업이익증가", "무상증자", 
        "자사주소각", "자사주취득", "인수", "합병","단일판매"
    ]

    print("\n" + "="*65)
    print(" [ DART 상장사 실시간 흑자/적자 & 호재 검색 시스템 ] ")
    print("="*65)
    print(" 1. 전체 공시 조회 (보정된 날짜 기준)")
    print(" 2. 종목명/코드 검색")
    print(" 3. 핵심 호재 공시")
    print("="*65)
    
    choice = input(" 메뉴 선택: ").strip()
    search_val = ""
    if choice == '2':
        search_val = input(" 검색어(회사명 또는 종목코드): ").strip()

    all_list = []
    for page in range(1, 4):
        url = "https://opendart.fss.or.kr/api/list.json"
        # ✅ today 대신 보정된 search_date 사용
        params = {'crtfc_key': api_key, 'bgnde': search_date, 'endde': search_date, 'page_count': '100', 'page_no': str(page)}
        try:
            res = requests.get(url, params=params, verify=False)
            data = res.json()
            if data.get('status') == '000':
                all_list.extend(data.get('list', []))
                if len(data.get('list', [])) < 100: break
            else: break
        except: break

    if not all_list:
        print(f" {search_date} 공시 데이터가 없거나 불러오지 못했습니다.")
        return

    sorted_list = sorted(all_list, key=lambda x: x['rcept_no'], reverse=True)
    count = 0
    print(f"\n[*] {search_date} 공시 {len(sorted_list)}건 분석 시작...\n" + "-"*85)

    for item in sorted_list:
        if item['corp_cls'] not in ['Y', 'K', 'N']: continue

        corp_name = item['corp_name']
        report_nm = item['report_nm']
        corp_code = item['corp_code']
        market = {"Y": "코스피", "K": "코스닥", "N": "코넥스"}.get(item['corp_cls'])

        show = False
        if choice == '1': 
            show = True
        elif choice == '2':
            if search_val.replace(" ","") in corp_name.replace(" ","") or \
               search_val.replace(" ","") in report_nm.replace(" ",""): 
                show = True
        elif choice == '3':
            if any(k in report_nm.replace(" ", "") for k in GOOD_KEYWORDS):
                show = True

        if show:
            count += 1
            profit_status = get_current_profit_status(api_key, corp_code)
            link = f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={item['rcept_no']}"
            
            print(f"{count:2d}. <{market}> {corp_name} {profit_status}")
            print(f"    - {report_nm}")
            print(f"    - 링크: {link}")
            print("-" * 85)

    if count == 0: 
        print(" 조건에 맞는 공시가 없습니다.")
    else:
        print(f"\n>>> 검색된 결과: 총 {count}건")

if __name__ == "__main__":
    start_dart_system()