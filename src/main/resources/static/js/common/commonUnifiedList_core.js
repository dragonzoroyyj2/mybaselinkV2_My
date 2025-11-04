/* ===============================================================
   ✅ commonUnifiedList_core.js (v1.9 - 2025.11 완전 분리판)
   ---------------------------------------------------------------
   - UnifiedList 클래스 기본 구조 정의
   - CRUD / loadList / 서버통신 / 캐시 관리
   - client/server 모드 완전 구분
   - 다른 JS 모듈(commonUnifiedList_ui.js 등)과 연동됨
================================================================ */

class UnifiedList {
  constructor(config) {
    this.reinit(config);
  }

  /* ----------------------------------------------------------
     ♻️ 초기화
     ----------------------------------------------------------
     - config 다시 로드 및 전역 변수 초기화
     - 이벤트/버튼/UI 초기 상태 설정
  ---------------------------------------------------------- */
  reinit(config) {
    this.config = config;
    this.pageSize = config.pageSize || 10;
    this.pageGroupSize = config.pageGroupSize || 5;
    this.currentPage = 0;
    this._clientData = null;
    this.lastSearch = ""; // ✅ 검색어 기억
    this.csrfToken = document.querySelector("meta[name='_csrf']")?.content;
    this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;

    // 외부 함수들(UI, Event 등) prototype 확장 메서드로 연결됨
    this._bindGlobalEvents();
    this.toggleButtons();
    this.loadList(0);
  }

  /* ----------------------------------------------------------
     📥 리스트 조회 (v1.9 수정판)
     ----------------------------------------------------------
     - ✅ mode: "client" → 최초 1회 서버 요청 후 캐싱
     - ✅ mode: "server" → 매 페이지마다 서버 요청
     - ✅ overlay 중복 방지 및 pointer-events 제어
  ---------------------------------------------------------- */
  async loadList(page = 0, _env = "web", search = "") {
    if (!search && this.lastSearch) search = this.lastSearch;
    else if (search) this.lastSearch = search;

    this.currentPage = page;

    const tbody = document.querySelector(this.config.tableBodySelector);
    if (!tbody) return;

    // ✅ client 모드일 때 이미 전체 데이터가 있다면 서버통신 생략
    if (this.config.mode === "client" && this._clientData && _env !== "force") {
      this._renderClientData();
      return;
    }

    // ✅ 로딩 오버레이 표시
    let overlay = document.querySelector(".global-loading-overlay");
    if (!overlay) {
      overlay = document.createElement("div");
      overlay.className = "global-loading-overlay";
      overlay.innerHTML = `
        <div class="global-spinner-wrap">
          <div class="spinner"></div>
        </div>`;
      document.body.appendChild(overlay);
    }
    overlay.style.display = "flex";
    overlay.style.pointerEvents = "auto";

    const startTime = Date.now();

    try {
      const url =
        `${this.config.apiUrl}?page=${page}&size=${this.pageSize}` +
        `&mode=${this.config.mode}&pagination=${this.config.pagination}` +
        `&search=${encodeURIComponent(search)}`;

      const res = await fetch(url, this._opts("GET"));
      if (!res.ok) throw new Error("조회 실패");
      const data = await res.json();

      const list = Array.isArray(data.content) ? data.content : [];

      // ✅ client 모드 → 데이터를 메모리에 저장 후 로컬 페이징
      if (this.config.mode === "client") {
        this._clientData = list;
        this._renderClientData();
      }
      // ✅ server 모드 → 서버 데이터 그대로 렌더링
      else {
        this.renderTable(list);
        this._renderPagination(data.totalPages || 1);
      }

      // ✅ 총 건수 표시
      const totalEl = document.getElementById("totalCount");
      if (totalEl)
        totalEl.textContent = `총 ${data.totalElements ?? list.length}건`;
    } catch (err) {
      console.error(err);
      tbody.innerHTML = `<tr><td colspan="100%">데이터 조회 오류</td></tr>`;
    } finally {
      const elapsed = Date.now() - startTime;
      const delay = Math.max(0, 100 - elapsed);
      setTimeout(() => {
        overlay.style.display = "none";
        overlay.style.pointerEvents = "none";
      }, delay);
    }
  }

  /* ----------------------------------------------------------
     🧩 CRUD 공통 옵션
     ----------------------------------------------------------
     - CSRF 자동 주입
     - JSON 자동 변환
  ---------------------------------------------------------- */
  _opts(method, body = null) {
    const headers = { "Content-Type": "application/json" };
    if (this.csrfHeader && this.csrfToken)
      headers[this.csrfHeader] = this.csrfToken;
    return { method, headers, body: body ? JSON.stringify(body) : undefined };
  }
}
