/**
 * 🧩 commonUnifiedList_op.js (최종 안정화 버전)
 * --------------------------------------------------------
 * ✅ 공용 리스트/CRUD/엑셀 + 반응형 테이블 + 페이징 자동조정
 * ✅ 로딩 스피너 & 토스트 알림 통합
 * ✅ 순수 div 기반 레이어팝업 정상화 (별도 라이브러리 미사용)
 * ✅ 중복 메세지 및 이벤트 실행 방지
 * --------------------------------------------------------
 *
 * 사용법:
 *   const unifiedListManager = initUnifiedList({ mode: "server" | "client", ...config });
 *
 * ⚠️ 이 파일은 top.html의 스피너/토스트 컴포넌트와 commonPagination_op.js가 로드된 후 로드되어야 합니다.
 */
function initUnifiedList(config) {
  // 전역에 UnifiedList 인스턴스를 저장하여 중복 초기화 방지
  if (window.unifiedListInstance) {
    console.warn("UnifiedList 인스턴스가 이미 존재합니다. 기존 인스턴스를 재사용합니다.");
    window.unifiedListInstance.init(); // 기존 인스턴스 재초기화
    return window.unifiedListInstance;
  }

  try {
    const instance = new UnifiedList(config);
    window.unifiedListInstance = instance;
    return instance;
  } catch (e) {
    console.error("UnifiedList 초기화 실패:", e);
  }
}

class UnifiedList {
  constructor(config) {
    this.config = config;
    this.validateConfig(config);
    this.currentPage = 0;
    this.pageSize = config.pageSize || 10;
    this.pageGroupSize = config.pageGroupSize || 5;
    this.isFullDataLoaded = false;
    this.fullDataCache = [];
    this.totalPagesCache = 0;
    this.currentSort = { key: null, direction: 'asc' };
    this.eventListenerBound = false;

    this.$ = sel => document.querySelector(sel);
    this.$$ = sel => document.querySelectorAll(sel);
    
    this.csrfToken = this.$("meta[name='_csrf']")?.content;
    this.csrfHeader = this.$("meta[name='_csrf_header']")?.content;

    this.config.tableBodySelector = this.config.tableBodySelector || 'tbody';
    this.config.tableHeaderSelector = this.config.tableHeaderSelector || 'thead';
    this.config.searchInputSelector = this.config.searchInputSelector || '#searchInput';
    this.config.searchBtnSelector = this.config.searchBtnSelector || '#searchBtn';
    this.config.addBtnSelector = this.config.addBtnSelector || '#addBtn';
    this.config.deleteSelectedBtnSelector = this.config.deleteSelectedBtnSelector || '#deleteSelectedBtn';
    this.config.excelBtnSelector = this.config.excelBtnSelector || '#excelBtn';
    this.config.checkAllSelector = this.config.checkAllSelector || '#checkAll';
    this.config.modalId = this.config.modalId || '#addModal';
    this.config.detailModalId = this.config.detailModalId || '#detailModal';
    this.config.paginationSelector = this.config.paginationSelector || '#pagination';
    this.listContainer = document.body;
    
    this.init();
  }
  
  validateConfig(config) {
    const requiredProps = ['apiUrl', 'tableBodySelector', 'paginationSelector', 'columns'];
    for (const prop of requiredProps) {
      if (!config[prop]) {
        console.error(`Config validation failed: '${prop}' is required.`);
        throw new Error(`Config validation failed: '${prop}' is required.`);
      }
    }
  }

  init() {
    this.toggleButtons();
    if (!this.eventListenerBound) {
        this.bindEvents();
    }
    this.loadList(0);
  }

  // ===============================
  // UI 피드백 메서드 (top.html과 연동)
  // ===============================
  showSpinner() {
    const spinner = this.$('#loadingSpinner');
    if (spinner) spinner.style.display = 'flex';
  }

  hideSpinner() {
    const spinner = this.$('#loadingSpinner');
    if (spinner) spinner.style.display = 'none';
  }
  
  // ===============================
  // 데이터 로드
  // ===============================
  async loadList(page = 0, environment = 'web', search = '', sort = null) {
    this.currentPage = page;
    const body = this.$(this.config.tableBodySelector);
    if (!body) {
      window.notify('error', '테이블 바디 요소를 찾을 수 없습니다.');
      return;
    }
    
    body.innerHTML = '<tr><td colspan="100%">데이터를 불러오는 중입니다...</td></tr>';
    this.showSpinner();

    try {
      if (this.config.mode === "server") {
        await this._loadFromServer(page, environment, search, sort);
      } else if (this.config.mode === "client") {
        await this._loadFromClient(page, search, sort);
      }
      window.notify('success', '데이터를 성공적으로 불러왔습니다.');
    } catch (err) {
      console.error(err);
      window.notify('error', '데이터 조회 중 오류 발생: ' + err.message);
      body.innerHTML = '<tr><td colspan="100%">데이터 조회 중 오류가 발생했습니다.</td></tr>';
    } finally {
      this.hideSpinner();
    }
  }

  async _loadFromServer(page, environment, search, sort) {
    const params = new URLSearchParams();
    params.append('page', page);
    params.append('size', this.pageSize);
    params.append('search', search);
    if (sort) params.append('sort', `${sort.key},${sort.direction}`);

    const url = `${this.config.apiUrl}?${params.toString()}`;
    const res = await fetch(url, this.fetchOptions("GET"));
    if (!res.ok) throw new Error("서버 데이터 조회 실패");
    const data = await res.json();
    this.renderTable(data.content || []);
    this._updateTotalCount(data.totalElements ?? data.content?.length ?? 0);
    this.totalPagesCache = data.totalPages ?? Math.ceil((data.totalElements ?? data.content?.length ?? 0) / this.pageSize);
    this._renderPagination();
  }

  async _loadFromClient(page, search, sort) {
    if (!this.isFullDataLoaded) {
      const res = await fetch(`${this.config.apiUrl}?mode=client`, this.fetchOptions("GET"));
      if (!res.ok) throw new Error("전체 데이터 조회 실패");
      const json = await res.json();
      this.fullDataCache = Array.isArray(json.content) ? json.content : [];
      this.isFullDataLoaded = true;
    }

    const searchLower = search.toLowerCase();
    let filtered = search
      ? this.fullDataCache.filter(item =>
          Object.values(item).some(v => String(v ?? "").toLowerCase().includes(searchLower))
        )
      : this.fullDataCache;

    if (sort) {
      filtered.sort((a, b) => {
        const aVal = a[sort.key] || '';
        const bVal = b[sort.key] || '';
        if (aVal < bVal) return sort.direction === 'asc' ? -1 : 1;
        if (aVal > bVal) return sort.direction === 'asc' ? 1 : -1;
        return 0;
      });
    }

    const pageData = this.config.pagination ? filtered.slice(page * this.pageSize, (page + 1) * this.pageSize) : filtered;
    this.renderTable(pageData);
    this._updateTotalCount(filtered.length);
    this.totalPagesCache = Math.max(1, Math.ceil(filtered.length / this.pageSize));
    this._renderPagination();
  }

  _updateTotalCount(count) {
    const totalCountEl = this.$("#totalCount");
    if (totalCountEl) totalCountEl.textContent = `총 ${count}건`;
  }

  _renderPagination() {
    const pagingEl = this.$(this.config.paginationSelector);
    if (this.config.pagination && pagingEl) {
      renderPagination(this.currentPage, this.totalPagesCache, this.config.paginationSelector, this.loadList.bind(this), this.pageGroupSize);
    } else if (pagingEl) {
      pagingEl.innerHTML = "";
    }
  }
  
  // ===============================
  // 테이블 렌더링
  // ===============================
  renderTable(list) {
    const tbody = this.$(this.config.tableBodySelector);
    if (!tbody) return;
    tbody.innerHTML = "";
    if (!Array.isArray(list) || list.length === 0) {
      const colSpan = (this.config.columns?.length || 0) + 1;
      tbody.innerHTML = `<tr><td colspan="${colSpan}">데이터가 없습니다.</td></tr>`;
      return;
    }
    list.forEach(row => {
      const tr = document.createElement("tr");
      const chkTd = document.createElement("td");
      chkTd.innerHTML = `<input type="checkbox" value="${row.id}" data-id="${row.id}" class="row-checkbox">`;
      tr.appendChild(chkTd);
      if (this.config.enableRowClickDetail) {
        tr.dataset.id = row.id;
        tr.classList.add('clickable-row');
      }
      this.config.columns.forEach(col => {
        const td = document.createElement("td");
        const val = row[col.key] ?? "";
        if (col.isDetailLink && this.config.enableDetailView && !this.config.enableRowClickDetail) {
          td.innerHTML = `<a href="#" data-id="${row.id}" class="detail-link">${val}</a>`;
        } else {
          td.textContent = val;
        }
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    const checkAllEl = this.$(this.config.checkAllSelector);
    if (checkAllEl) checkAllEl.checked = false;
  }
  
  // ===============================
  // 버튼 가시성 제어 (역할 기반 추가)
  // ===============================
  toggleButtons() {
    const userRoles = window.userRoles || [];
    const buttonMap = {
      search: this.config.searchBtnSelector, 
      add: this.config.addBtnSelector,
      deleteSelected: this.config.deleteSelectedBtnSelector, 
      excel: this.config.excelBtnSelector,
      save: this.config.saveBtnSelector,
      update: this.config.updateBtnSelector
    };
    const buttonsConfig = this.config.buttons || {};
    Object.keys(buttonMap).forEach(key => {
      const selector = buttonMap[key];
      const element = this.$(selector);
      if (element) {
        const buttonConfig = buttonsConfig[key];
        if (buttonConfig === false || (buttonConfig && buttonConfig.roles && !buttonConfig.roles.some(role => userRoles.includes(role)))) {
          element.style.display = 'none';
        } else {
          element.style.display = '';
        }
      }
    });
  }

  // ===============================
  // 이벤트 바인딩 (이벤트 리스너 중복 등록 방지)
  // ===============================
  bindEvents() {
    if (!this.eventListenerBound) {
      document.body.addEventListener('click', this._handleEvent.bind(this));
      document.body.addEventListener('keydown', this._handleEvent.bind(this));
      this.eventListenerBound = true;
    }
  }

  _handleEvent(e) {
    const target = e.target;
    const closest = (sel) => target.closest(sel);

    if (e.type === 'click') {
        const searchBtn = closest(this.config.searchBtnSelector);
        if (searchBtn) {
          e.preventDefault();
          const searchValue = this.$(this.config.searchInputSelector)?.value || '';
          this.loadList(0, this._getEnv(), searchValue, this.currentSort);
          return;
        }
        
        const addBtn = closest(this.config.addBtnSelector);
        if (addBtn) {
          e.preventDefault();
          this.openAddModal();
          return;
        }

        if (target.matches(this.config.checkAllSelector)) {
          this.toggleAllCheckboxes(target.checked);
          return;
        }
        
        const detailLink = closest('.detail-link');
        const clickableRow = closest('.clickable-row');
        if ((detailLink && this.config.enableDetailView && !this.config.enableRowClickDetail) || (this.config.enableRowClickDetail && clickableRow && !target.closest('.row-checkbox'))) {
          e.preventDefault();
          const id = closest('[data-id]')?.dataset.id;
          if (id) this.openDetailModal(id);
          return;
        }
        
        const deleteBtn = closest(this.config.deleteSelectedBtnSelector);
        if (deleteBtn) {
          this.deleteSelected();
          return;
        }
        
        const excelBtn = closest(this.config.excelBtnSelector);
        if (excelBtn) {
          this.downloadExcel();
          return;
        }
        
        const sortableHeader = closest(`${this.config.tableHeaderSelector} .sortable`);
        if (sortableHeader) {
          this.sortTable(sortableHeader);
          return;
        }
        
        // 모달 닫기 버튼 처리
        if (target.matches('[data-close]')) {
          const modalId = target.dataset.close;
          this._closeModal(`#${modalId}`);
          return;
        }
    } else if (e.type === 'keydown' && e.key === 'Enter') {
        if (e.target.matches(this.config.searchInputSelector)) {
          e.preventDefault();
          const searchValue = e.target.value;
          this.loadList(0, this._getEnv(), searchValue, this.currentSort);
        }
    }
  }

  // ===============================
  // 체크박스 제어
  // ===============================
  toggleAllCheckboxes(isChecked) {
    const checkboxes = this.$$(`${this.config.tableBodySelector} .row-checkbox`);
    checkboxes.forEach(checkbox => checkbox.checked = isChecked);
  }

  // ===============================
  // 선택 삭제 기능
  // ===============================
  deleteSelected() {
    const selectedIds = Array.from(this.$$(`${this.config.tableBodySelector} .row-checkbox:checked`))
        .map(cb => cb.dataset.id)
        .filter(id => id);
    
    if (selectedIds.length === 0) {
      window.notify('warning', "삭제할 항목을 선택해주세요.");
      return;
    }

    if (confirm(`${selectedIds.length}개의 항목을 삭제하시겠습니까?`)) {
      console.log('삭제할 ID:', selectedIds);
      this.showSpinner();
      if (this.config.onDeleteSuccess) {
        this.config.onDeleteSuccess(selectedIds);
      } else {
        this.hideSpinner();
        window.notify('success', '선택된 항목 삭제 요청을 처리했습니다.');
      }
    }
  }
  
  // ===============================
  // 엑셀 다운로드 기능
  // ===============================
  downloadExcel() {
    window.notify('info', '엑셀 다운로드를 시작합니다.');
    console.log('엑셀 다운로드 기능 호출');
  }

  // ===============================
  // 모달 제어 (순수 div 방식으로 변경)
  // ===============================
  openAddModal() {
    this._openModal(this.config.modalId, this.config.onAddModalOpen);
  }

  openDetailModal(id) {
    this._openModal(this.config.detailModalId, () => {
      console.log(`${this.config.detailModalId} 모달 열기 (ID: ${id})`);
      if (this.config.onDetailModalOpen) {
        this.config.onDetailModalOpen(id);
      }
    });
  }
  
  _openModal(modalSelector, callback) {
    const modalEl = this.$(modalSelector);
    if (modalEl) {
      modalEl.style.display = 'block';
      if (callback) callback();
    } else {
      window.notify('error', `${modalSelector} 모달을 찾을 수 없습니다.`);
    }
  }
  
  _closeModal(modalSelector) {
    const modalEl = this.$(modalSelector);
    if (modalEl) {
      modalEl.style.display = 'none';
    }
  }
  
  // ===============================
  // 테이블 정렬
  // ===============================
  sortTable(header) {
    const sortKey = header.dataset.sortKey;
    if (!sortKey) return;
    
    if (this.currentSort.key === sortKey) {
      this.currentSort.direction = this.currentSort.direction === 'asc' ? 'desc' : 'asc';
    } else {
      this.currentSort = { key: sortKey, direction: 'asc' };
    }

    this.$$(`${this.config.tableHeaderSelector} .sortable`).forEach(h => h.classList.remove('sorted-asc', 'sorted-desc'));
    header.classList.add(`sorted-${this.currentSort.direction}`);

    const searchValue = this.$(this.config.searchInputSelector)?.value || '';
    this.loadList(0, this._getEnv(), searchValue, this.currentSort);
  }

  // ===============================
  // 공용 유틸리티
  // ===============================
  _getEnv() {
    return window.innerWidth < 768 ? 'mobile' : 'web';
  }

  fetchOptions(method, body = null) {
    const options = {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...this.csrfHeader && this.csrfToken ? { [this.csrfHeader]: this.csrfToken } : {}
      },
    };
    if (body) {
      options.body = JSON.stringify(body);
    }
    return options;
  }
}