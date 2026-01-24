# -*- coding: utf-8 -*-
import urllib.request
import json
import urllib.parse
import sys

# 네이버 API 설정
NAVER_CLIENT_ID = "FVzkwJZt2usCrma3m5by"
NAVER_CLIENT_SECRET = "CnkokvjlJB"

MAJOR_KEYWORDS = [
    "수주", "공급계약", "흑자전환", "공시", "M&A", "MOU", "투자",
    "상한가", "특징주", "독점", "유상증자", "국책과제", "무상증자", "인수", "단일판매"
]

def fetch_news(query):
    encText = urllib.parse.quote(query)
    url = f"https://openapi.naver.com/v1/search/news.json?query={encText}&display=30&sort=date"
    req = urllib.request.Request(url)
    req.add_header("X-Naver-Client-Id", NAVER_CLIENT_ID)
    req.add_header("X-Naver-Client-Secret", NAVER_CLIENT_SECRET)
    try:
        res = urllib.request.urlopen(req, timeout=2)
        return json.loads(res.read().decode('utf-8')).get('items', [])
    except:
        return []

# 자바에서 인자로 검색어를 넘겨줌 (없으면 "" 또는 "1")
user_input = sys.argv[1].strip() if len(sys.argv) > 1 else ""
if user_input == "1": user_input = ""

search_list = [user_input] if user_input else MAJOR_KEYWORDS
seen_links = set()
results = []

for word in search_list:
    items = fetch_news(word)
    for item in items:
        # 제목 태그 제거 및 정리
        title = item['title'].replace('<b>','').replace('</b>','').replace('&quot;','"').replace('&amp;','&').replace('&#39;','\'')
        link = item['link']
        
        if link not in seen_links:
            is_relevant = False
            if user_input:
                if user_input.replace(" ","") in title.replace(" ",""): 
                    is_relevant = True
            else:
                if any(k in title for k in MAJOR_KEYWORDS): 
                    is_relevant = True
            
            if is_relevant:
                # 자바 서비스 규격에 맞게 맵핑 정보 구성
                results.append({
                    "title": title,
                    "link": link,
                    "pubDate": item['pubDate']
                })
                seen_links.add(link)

# 자바가 읽을 수 있도록 최종 리스트만 JSON으로 출력
print(json.dumps(results, ensure_ascii=False))