/* ===============================================================
   ✅ commonUnifiedList_loadseq_1_init.js (v1.11 - 2025.11 정성형 안정판)
   ---------------------------------------------------------------
   - UnifiedList 클래스 (Core + Data Load)
   - client/server 모드 완전 분리
   - ✅ safeFetch() 적용 (비정상 API 요청 차단)
   - ✅ 403 발생 원인인 customUrl 문자열 오염 방지
   - ✅ 기존 모든 기능, 주석, 구조 완전 보존
================================================================ */

/* ---------------------------------------------------------------
   🌐 안전한 fetch 래퍼 (전역 등록)
   --------------------------------------------------------------- */
if (!window.safeFetch) {
  window.safeFetch = async (url, opts = {}) => {
    // 🚫 잘못된 경로를 사전에 차단 (/api/ 로 시작하지 않으면 무시)
    if (typeof url !== "string" || !url.startsWith("/api/")) {
      console.warn("🚫 비정상 API 호출 차단:", url);
      Toast?.show?.("잘못된 요청 경로가 차단되었습니다.", "warning");
      return Promise.reject(new Error("Invalid API path"));
    }

    try {
      const response = await fetch(url, opts);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return response;
    } catch (err) {
      console.error("❌ safeFetch 실패:", err);
      Toast?.show?.("서버 통신 중 오류가 발생했습니다.", "error");
      throw err;
    }
  };
}

/* ---------------------------------------------------------------
   🧩 초기화 함수 (Singleton 구조)
---------------------------------------------------------------- */
function initUnifiedList(config) {
  if (window.unifiedListInstance) {
    window.unifiedListInstance.reinit(config);
    return window.unifiedListInstance;
  }
  const inst = new UnifiedList(config);
  window.unifiedListInstance = inst;
  return inst;
}

/* ---------------------------------------------------------------
   🧭 UnifiedList 클래스 정의
---------------------------------------------------------------- */
class UnifiedList {
  constructor(config) {
    this.reinit(config);
  }

  /* ============================================================
     ✅ 초기화 / 재초기화
  ============================================================ */
  reinit(config) {
    this.config = config;
    this.pageSize = config.pageSize || 10;
    this.pageGroupSize = config.pageGroupSize || 5;
    this.currentPage = 0;
    this._clientData = null;
    this.lastSearch = ""; // ✅ 검색어 기억
    this.csrfToken = document.querySelector("meta[name='_csrf']")?.content;
    this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;

    // 이벤트 초기화
    this._bindGlobalEvents();
    this.toggleButtons();
    this.loadList(0);
  }

  /* ============================================================
     ✅ 버튼 토글 (표시/비활성화)
  ============================================================ */
  toggleButtons() {
    const btns = this.config.buttons || {};
    const getSel = s => (s ? document.querySelector(s) : null);

    const mapping = {
      searchInput: this.config.searchInputSelector,
      search: this.config.searchBtnSelector,
      add: this.config.addBtnSelector,
      deleteSelected: this.config.deleteSelectedBtnSelector,
      excel: this.config.excelBtnSelector,
    };

    Object.entries(mapping).forEach(([key, sel]) => {
      const el = getSel(sel);
      if (!el) return;
      el.style.display = btns[key] ? "" : "none";
    });
  }

  /* ============================================================
     ✅ 리스트 로딩 (Client / Server 모드 완전 분리)
  ============================================================ */
  async loadList(page = 0, customUrl = null, query = "") {
    const cfg = this.config;
    let apiUrl = cfg.apiUrl;
    const searchInput = document.querySelector(cfg.searchInputSelector);
    const searchVal = (searchInput?.value || "").trim();
    this.lastSearch = searchVal;

    CommonLoading?.show?.("table");

    try {
      let data = { content: [], totalElements: 0, totalPages: 1 };

      /* -----------------------------------------------
         🧭 1. customUrl 검증 (비정상 문자열 차단)
      ----------------------------------------------- */
      if (customUrl && typeof customUrl === "string") {
        if (customUrl.startsWith("/api/")) {
          apiUrl = customUrl;
        } else {
          console.warn("🚫 비정상 customUrl 무시:", customUrl);
        }
      }

      /* -----------------------------------------------
         🧭 2. Client 모드: 전체 데이터 로컬 페이징
      ----------------------------------------------- */
      if (cfg.mode === "client") {
        const res = await safeFetch(apiUrl);
        const all = await res.json();
        this._clientData = Array.isArray(all.content) ? all.content : [];

        // ✅ 검색 필터
        let filtered = [...this._clientData];
        if (searchVal) {
          const lower = searchVal.toLowerCase();
          filtered = filtered.filter(row =>
            Object.values(row).some(v => String(v).toLowerCase().includes(lower))
          );
        }

        // ✅ 페이징 계산
        const totalElements = filtered.length;
        const totalPages = Math.ceil(totalElements / this.pageSize);
        const start = page * this.pageSize;
        const end = Math.min(start + this.pageSize, totalElements);
        const paged = filtered.slice(start, end);

        data = { content: paged, totalElements, totalPages };
      }
      /* -----------------------------------------------
         🧭 3. Server 모드: 페이지 단위 요청
      ----------------------------------------------- */
      else {
        const params = new URLSearchParams({
          page,
          size: this.pageSize,
          search: searchVal,
          pagination: cfg.pagination ? "true" : "false",
          mode: "server",
        });
        const res = await safeFetch(`${apiUrl}?${params.toString()}`);
        data = await res.json();
      }

      /* -----------------------------------------------
         🧭 4. totalPages 보정 (client 모드)
      ----------------------------------------------- */
      if (cfg.mode === "client" && Array.isArray(data.content)) {
        const totalElements = data.totalElements || data.content.length;
        data.totalElements = totalElements;
        data.totalPages = Math.ceil(totalElements / this.pageSize);
      }

      /* -----------------------------------------------
         🧭 5. 렌더링
      ----------------------------------------------- */
      this.renderTable(data.content);

      renderPagination(
        page,
        data.totalPages,
        cfg.paginationSelector,
        this.loadList.bind(this),
        this.pageGroupSize
      );

      const totalCountEl = document.querySelector("#totalCount");
      if (totalCountEl) totalCountEl.textContent = `총 ${data.totalElements}건`;

      this.currentPage = page;
    } catch (e) {
      console.error("❌ loadList 실패:", e);
      Toast?.show?.("데이터 로드 실패", "error");
    } finally {
      CommonLoading?.hide?.();
    }
  }

  /* ============================================================
     ✅ 전역 이벤트 바인딩
  ============================================================ */
  _bindGlobalEvents() {
    const cfg = this.config;

    // 🔍 검색 버튼
    const searchBtn = document.querySelector(cfg.searchBtnSelector);
    if (searchBtn) searchBtn.onclick = () => this.loadList(0);

    // ⌨️ Enter 키 검색
    const searchInput = document.querySelector(cfg.searchInputSelector);
    if (searchInput) {
      searchInput.addEventListener("keydown", e => {
        if (e.key === "Enter") this.loadList(0);
      });
    }

    // ➕ 등록 버튼
    const addBtn = document.querySelector(cfg.addBtnSelector);
    if (addBtn) {
      addBtn.onclick = () => {
        const modal = document.querySelector(cfg.modalId);
        if (modal) modal.style.display = "flex";
        cfg.onAddModalOpen?.();
      };
    }

    // 💾 저장 버튼
    const saveBtn = document.querySelector(cfg.saveBtnSelector);
    if (saveBtn) {
      saveBtn.onclick = () => {
        Toast?.show?.("등록되었습니다.", "success");
        const modal = document.querySelector(cfg.modalId);
        if (modal) modal.style.display = "none";
      };
    }

    // ❌ 닫기 버튼
    document.querySelectorAll(cfg.closeBtnSelector).forEach(btn => {
      btn.addEventListener("click", e => {
        const id = e.target.dataset.close;
        const modal = document.getElementById(id);
        if (modal) modal.style.display = "none";
      });
    });

    // 🧾 엑셀 다운로드
    const excelBtn = document.querySelector(cfg.excelBtnSelector);
    if (excelBtn) {
      excelBtn.onclick = () => {
        const search = this.lastSearch || "";
        const excelUrl = `${cfg.apiUrl.replace("/list", "/excel")}?search=${encodeURIComponent(search)}`;
        safeFetch(excelUrl)
          .then(() => {
            window.open(excelUrl);
          })
          .catch(() => {
            Toast?.show?.("엑셀 요청 실패", "error");
          });
      };
    }

    // 🗑️ 선택 삭제
    const delBtn = document.querySelector(cfg.deleteSelectedBtnSelector);
    if (delBtn) {
      delBtn.onclick = () => {
        Toast?.show?.("선택 항목이 삭제되었습니다.", "info");
      };
    }
  }

  /* ============================================================
     ✅ 테이블 렌더링
  ============================================================ */
  renderTable(list) {
    const cfg = this.config;
    const tbody = document.querySelector(cfg.tableBodySelector);
    if (!tbody) return;
    tbody.innerHTML = "";

    if (!list || list.length === 0) {
      tbody.innerHTML = `<tr><td colspan="${cfg.columns.length}" class="empty">데이터가 없습니다.</td></tr>`;
      return;
    }

    list.forEach(row => {
      const tr = document.createElement("tr");
      cfg.columns.forEach(col => {
        const td = document.createElement("td");
        td.textContent = row[col.key] ?? "";
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
  }
}
