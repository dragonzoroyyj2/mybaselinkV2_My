/* ===============================================================
   ✅ common_loadseq_5_UnifiedList_op.js (v1.9 - onRenderSuccess 콜백 추가)
   ---------------------------------------------------------------
   [핵심 수정 사항]
   - renderTable 로직이 끝나는 시점에 onRenderSuccess 콜백 실행.
   - 데이터가 없을 때도 콜백이 호출되도록 처리하여 예외 케이스 대응.
   - formatter/render를 통한 HTML 렌더링 지원 유지.
   - 원본 들여쓰기 및 주석 100% 유지.
================================================================ */

function initUnifiedList(config) {
    if (window.unifiedListInstance) {
        window.unifiedListInstance.reinit(config);
        return window.unifiedListInstance;
    }
    const inst = new UnifiedList(config);
    window.unifiedListInstance = inst;
    return inst;
}

class UnifiedList {
    constructor(config) {
        this.reinit(config);
    }

    reinit(config) {
        this.config = config;
        this.pageSize = config.pageSize || 10;
        this.pageGroupSize = config.pageGroupSize || 5;
        this.currentPage = 0;
        this._clientData = null;
        this.lastSearch = ""; // ✅ 검색어 기억
        this.csrfToken = document.querySelector("meta[name='_csrf']")?.content;
        this.csrfHeader = document.querySelector("meta[name='_csrf_header']")?.content;

        this._bindGlobalEvents();
        this.toggleButtons();
        this.loadList(0);
    }

    /* ----------------------------------------------------------
       🖱️ 전역 이벤트 (항상 유지)
    ---------------------------------------------------------- */
    _bindGlobalEvents() {
        document.body.removeEventListener("click", this._clickHandler);
        document.body.removeEventListener("keydown", this._keyHandler);
        this._clickHandler = (e) => this._onClick(e);
        this._keyHandler = (e) => this._onKey(e);
        document.body.addEventListener("click", this._clickHandler);
        document.body.addEventListener("keydown", this._keyHandler);
    }

    /* ----------------------------------------------------------
       📥 리스트 조회 (v1.1 수정판)
    ---------------------------------------------------------- */
    async loadList(page = 0, _env = "web", search = "") {
        // 🔹 검색어 유지 로직
        if (!search && this.lastSearch) search = this.lastSearch;
        else if (search) this.lastSearch = search;

        // 🔹 현재 페이지 기록
        this.currentPage = page;

        const tbody = document.querySelector(this.config.tableBodySelector);
        if (!tbody) return;

        // ✅ 1. 전역 로딩 오버레이 표시 (화면 중앙 로딩바)
        const globalOverlay = document.querySelector(this.config.globalLoadingSelector || "#globalLoading");
        if (globalOverlay) {
            globalOverlay.style.display = "flex";
            globalOverlay.style.pointerEvents = "auto";
        }

        // ✅ client 모드일 때 이미 전체 데이터가 있다면 서버통신 생략
        if (this.config.mode === "client" && this._clientData && _env !== "force") {
            this._renderClientData();
            this._hideGlobalOverlay(); // 메모리 로딩 시에도 오버레이 제거
            return;
        }

        // ✅ 2. 테이블 내부 전용 로딩 오버레이 표시
        const tableContainer = tbody.closest(".table-container");
        let overlay = tableContainer?.querySelector(".table-loading-overlay");

        if (!overlay && tableContainer) {
            overlay = document.createElement("div");
            overlay.className = "table-loading-overlay";
            overlay.innerHTML = `
                <div class="table-spinner-wrap">
                  <div class="spinner"></div>
                </div>`;
            tableContainer.appendChild(overlay);
        }

        if (overlay) {
            overlay.style.display = "flex";
            overlay.style.pointerEvents = "auto";
        }

        const startTime = Date.now();

        try {
            // ✅ 서버 호출은 server 모드이거나 client 모드의 최초 1회만 수행
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
            tbody.innerHTML = `<tr><td colspan="100%" style="text-align:center; padding:100px 0;">데이터 조회 오류</td></tr>`;
        } finally {
            const elapsed = Date.now() - startTime;
            const delay = Math.max(0, 100 - elapsed);
            setTimeout(() => {
                if (overlay) {
                    overlay.style.display = "none";
                    overlay.style.pointerEvents = "none";
                }
                // ✅ 전역 오버레이 확실히 제거
                this._hideGlobalOverlay();
            }, delay);
        }
    }

    _renderClientData() {
        const tbody = document.querySelector(this.config.tableBodySelector);
        if (!tbody) return;
        const list = Array.isArray(this._clientData) ? this._clientData : [];

        if (this.config.pagination === false) {
            this.renderTable(list);
            const pg = document.querySelector(this.config.paginationSelector);
            if (pg) pg.innerHTML = "";
        } else {
            const start = this.currentPage * this.pageSize;
            const end = start + this.pageSize;
            this.renderTable(list.slice(start, end));
            this._renderPagination(Math.ceil(list.length / this.pageSize));
        }
    }

    /**** 데이터조회 공통 테이블 렌더 *****/
	renderTable(list) {
	    const tbody = document.querySelector(this.config.tableBodySelector);
	    const table = tbody.closest("table");
	    const thead = table.querySelector("#headerTable");
	    const loading = document.getElementById("globalLoading"); // 로딩바 객체 미리 확보
	    
	    tbody.innerHTML = "";

	    // 🚩 1. 헤더(thead) 자동 생성 로직 보강
	    if (thead) {
	        thead.innerHTML = ""; 
	        const headerTr = document.createElement("tr");
	        const hasCheckbox = (this.config.columns || []).some(c => c.checkbox === true);

	        if (hasCheckbox) {
	            const thCheck = document.createElement("th");
	            thCheck.style.width = "40px";
	            thCheck.innerHTML = `<input type="checkbox" id="checkAll">`;
	            headerTr.appendChild(thCheck);
	        }

	        (this.config.columns || []).forEach((col) => {
	            if (col.checkbox === true || col.hidden === true) return;
	            const th = document.createElement("th");
	            th.textContent = col.label || "";
	            if (col.width) {
	                th.style.width = col.width;
	                th.style.minWidth = col.width;
	            }
	            th.style.textAlign = col.headerAlign || "center";
	            headerTr.appendChild(th);
	        });
	        thead.appendChild(headerTr);
	    }

	    const visibleColCount = (this.config.columns || []).filter(c => !c.hidden && !c.checkbox).length;
	    const hasCheckbox = (this.config.columns || []).some(c => c.checkbox === true);

	    // 🚩 [수정 포인트] 데이터가 없는 경우 처리
	    if (!list || !list.length) {
	        const totalColspan = visibleColCount + (hasCheckbox ? 1 : 0);
	        // 🚩 패딩을 늘려 로딩바가 위치할 공간을 확보 (CSS의 min-height와 시너지)
	        tbody.innerHTML = `<tr><td colspan="${totalColspan}" style="text-align:center; padding:150px 0; color:#9ca3af; font-size:14px;">데이터가 존재하지 않습니다.</td></tr>`;
	        
	        if (loading) loading.style.display = "none";

            // 🚩 [v1.9] 데이터가 없어도 렌더링 시도는 끝났으므로 콜백 실행
            if (typeof this.config.onRenderSuccess === "function") {
                this.config.onRenderSuccess();
            }
	        return;
	    }

	    // 🚩 2. 데이터 행(row) 생성
	    list.forEach((row) => {
	        const tr = document.createElement("tr");
	        tr.dataset.rowData = JSON.stringify(row);
	        tr.dataset.id = row.id;

	        if (this.config.enableRowClickDetail) tr.classList.add("clickable-row");

	        if (hasCheckbox) {
	            const chk = document.createElement("td");
	            chk.innerHTML = `<input type="checkbox" class="row-checkbox" data-id="${row.id}">`;
	            tr.appendChild(chk);
	        }

	        (this.config.columns || []).forEach((col) => {
	            if (col.checkbox === true || col.hidden === true) return;

	            const td = document.createElement("td");
	            if (col.width) {
	                td.style.width = col.width;
	                td.style.minWidth = col.width;
	                td.style.maxWidth = col.width;
	            }
	            td.style.textAlign = col.align || "left";
	            if (col.fontWeight) td.style.fontWeight = col.fontWeight;
	            if (col.color) td.style.color = col.color;
	            if (col.fontSize) td.style.fontSize = col.fontSize;
	            
	            const val = row[col.columnId] ?? "";

                // 🚩 [v1.8] formatter 또는 render 함수가 있으면 실행 결과를 innerHTML로 삽입
                if (typeof col.formatter === "function") {
                    td.innerHTML = col.formatter(val, row);
                }
                else if (typeof col.render === "function") {
                    td.innerHTML = col.render(val, row);
                }
                // 🚩 isOpenPopup → isExternalLink 로 명칭 변경 적용
	            else if (col.isExternalLink === true) {
	                if (val && val !== "#") {
	                    td.innerHTML = `<a href="${val}" target="_blank" title="새창열기" style="color:#2563eb; font-size: 14px; text-decoration: none;">
	                                        <i class="fa-solid fa-arrow-up-right-from-square"></i> 이동
	                                    </a>`;
	                } else {
	                    td.innerHTML = `<span style="color:#9ca3af;">-</span>`;
	                }
	            } 
	            else if (col.isDetailLink) {
	                td.innerHTML = `<a href="#" class="detail-link" data-id="${row.id}" style="color:inherit; text-decoration:none; font-weight:inherit;">${val}</a>`;
	            } 
	            else {
	                td.textContent = val;
	            }
	            tr.appendChild(td);
	        });
	        tbody.appendChild(tr);
	    });

        // 🚩 [v1.9] 모든 데이터 행 생성이 완료된 직후 콜백 실행
        if (typeof this.config.onRenderSuccess === "function") {
            this.config.onRenderSuccess();
        }

	    const checkAll = document.querySelector("#checkAll");
	    if (checkAll) {
	        checkAll.addEventListener("change", (e) => {
	            const isChecked = e.target.checked;
	            tbody.querySelectorAll(".row-checkbox").forEach(chk => {
	                chk.checked = isChecked;
	            });
	        });
	    }

	    if (loading) {
	        loading.style.display = "none";
	    }
	}
  
    /* ... 이후 하단 메서드들은 원본 유지 ... */
    _renderPagination(totalPages) {
        this._lastTotalPages = totalPages;
        const el = document.querySelector(this.config.paginationSelector);
        if (!el) return;
        if (this.config.pagination === false) {
            el.innerHTML = "";
            return;
        }
        if (typeof renderPagination === "function") {
            renderPagination(
                this.currentPage,
                totalPages,
                this.config.paginationSelector,
                this.loadList.bind(this),
                this.pageGroupSize
            );
        } else el.innerHTML = "";
    }

    toggleButtons() {
        const map = {
            search: this.config.searchBtnSelector,
            add: this.config.addBtnSelector,
            deleteSelected: this.config.deleteSelectedBtnSelector,
            excel: this.config.excelBtnSelector,
        };
        const cfg = this.config.buttons || {};
        Object.keys(map).forEach((k) => {
            const el = document.querySelector(map[k]);
            if (el) el.style.display = cfg[k] === false ? "none" : "";
        });
    }

    _onKey(e) {
        if (e.key === "Enter" && e.target.matches(this.config.searchInputSelector)) {
            e.preventDefault();
            const s =
                document.querySelector(this.config.searchInputSelector)?.value || "";
            this._clientData = null;
            this.lastSearch = s;
            this.loadList(0, "web", s);
        }
    }

    _onClick(e) {
        const t = e.target,
            q = (sel) => t.closest(sel);

        if (q(this.config.searchBtnSelector)) {
            e.preventDefault();
            const s =
                document.querySelector(this.config.searchInputSelector)?.value || "";
            this._clientData = null;
            this.lastSearch = s;
            this.loadList(0, "web", s);
            return;
        }

        if (q(this.config.addBtnSelector)) {
            e.preventDefault();
            this.openAddModal();
            return;
        }

        if (t.matches(this.config.checkAllSelector)) {
            const checked = t.checked;
            document
                .querySelectorAll(`${this.config.tableBodySelector} .row-checkbox`)
                .forEach((cb) => (cb.checked = checked));
            return;
        }

        if (q(this.config.deleteSelectedBtnSelector)) {
            e.preventDefault();
            this.deleteSelected();
            return;
        }

        if (q(this.config.excelBtnSelector)) {
            e.preventDefault();
            this.downloadExcel();
            return;
        }

        if (t.classList.contains("detail-link")) {
            e.preventDefault();
            this.openDetailModal(t.dataset.id);
            return;
        }

        const row = t.closest(".clickable-row");
        if (this.config.enableRowClickDetail && row && !t.closest(".row-checkbox")) {
            this.openDetailModal(row.dataset.id);
            return;
        }

        if (t.matches("[data-close]")) {
            const id = t.dataset.close;
            this.closeModal(`#${id}`);
        }
    }

    openAddModal() {
        this.closeAllModals(true);
        const modal = document.querySelector(this.config.modalId);
        if (!modal) return;

        modal.style.display = "flex";
        modal.classList.add("active");
        document.body.classList.add("modal-open");

        this._resetFormFields(modal);

        const saveBtn = modal.querySelector("#saveBtn");
        if (saveBtn && !saveBtn._hasHandler) {
            saveBtn.addEventListener("click", () => this.saveData());
            saveBtn._hasHandler = true;
        }
    }

    async openDetailModal(id) {
        this.closeAllModals(true);
        const modal = document.querySelector(this.config.detailModalId);
        if (!modal) return;

        modal.style.display = "flex";
        modal.classList.add("active");
        document.body.classList.add("modal-open");
        this._showModalLoading(modal);

        this._resetFormFields(modal);
        
        const updateBtn = modal.querySelector("#updateBtn");
        if (updateBtn) {
            if (updateBtn._handler) {
                updateBtn.removeEventListener("click", updateBtn._handler);
                delete updateBtn._handler;
            }
            delete updateBtn._hasHandler;
        }

        try {
            const res = await fetch(`${this.config.apiUrl}/${id}`, this._opts("GET"));
            if (!res.ok) throw new Error("상세 조회 실패");
            const data = await res.json();

            Object.entries(data).forEach(([k, v]) => {
                const Cap = k.charAt(0).toUpperCase() + k.slice(1);
                const elements = modal.querySelectorAll(
                    `#detail${Cap}, [data-field='${k}'], [name='${k}']`
                );
                if (!elements.length) return;

                elements.forEach((el) => {
                    const tag = el.tagName.toLowerCase();
                    const type = el.type ? el.type.toLowerCase() : "";

                    if (tag === "input") {
                        switch (type) {
                            case "checkbox":
                                if (el.name) {
                                    const val = String(v ?? "");
                                    el.checked = (Array.isArray(v) && v.includes(el.value)) || 
                                                 (val.includes(el.value));
                                } else {
                                    el.checked = v === true || v === "true" || v === "Y" || v === "1";
                                }
                                break;
                            case "radio":
                                if (String(el.value) === String(v))
                                    el.checked = true;
                                break;
                            default:
                                el.value = v ?? "";
                        }
                    } else if (tag === "select" || tag === "textarea") {
                        el.value = v ?? ""; 
                    } else if (tag === "button") {
                        el.textContent = v ?? "";
                    } else {
                        if ("value" in el) el.value = v ?? "";
                        else el.textContent = v ?? "";
                    }
                });
            });

            if (updateBtn) {
                const handler = () => this.updateData(id);
                updateBtn.addEventListener("click", handler);
                updateBtn._handler = handler;
                updateBtn._hasHandler = true;
            }
        } catch (e) {
            console.error(e);
            Toast?.show?.("상세 조회 실패", "error");
        } finally {
            this._hideModalLoading(modal);
        }
    }

    closeAllModals(keepOne = false) {
        document.querySelectorAll(".modal").forEach((m) => {
            m.classList.remove("active");
            m.style.display = "none";
            this._resetFormFields(m);
            
            const updateBtn = m.querySelector("#updateBtn");
            if (updateBtn && updateBtn._handler) {
                updateBtn.removeEventListener("click", updateBtn._handler);
                delete updateBtn._handler;
                delete updateBtn._hasHandler;
            }
            const saveBtn = m.querySelector("#saveBtn");
            if (saveBtn && saveBtn._hasHandler) {
                delete saveBtn._hasHandler; 
            }
        });
        
        document.body.focus(); 
        window.scrollTo(0, 0); 
    }

    closeModal(sel) {
        const el = document.querySelector(sel);
        if (el) {
            el.classList.remove("active");
            el.style.display = "none";
            this._resetFormFields(el);
            
            const updateBtn = el.querySelector("#updateBtn");
            if (updateBtn && updateBtn._handler) {
                updateBtn.removeEventListener("click", updateBtn._handler);
                delete updateBtn._handler;
                delete updateBtn._hasHandler;
            }
            const saveBtn = el.querySelector("#saveBtn");
            if (saveBtn && saveBtn._hasHandler) {
                delete saveBtn._hasHandler;
            }
        }
        
        document.body.classList.remove("modal-open");
        this._hideGlobalOverlay();
        document.body.focus();
        window.scrollTo(0, 0);
    }

    _resetFormFields(modalEl) {
        modalEl.querySelectorAll('input,textarea,select').forEach(el => {
            const tag = el.tagName.toLowerCase();
            const type = el.type ? el.type.toLowerCase() : "";
            
            if (tag === 'input') {
                if (type === 'checkbox' || type === 'radio') {
                    el.checked = false;
                } else if (type !== 'submit' && type !== 'button') {
                    el.value = '';
                }
            } else if (tag === 'textarea') {
                el.value = '';
            } else if (tag === 'select') {
                el.selectedIndex = 0;
            }
            el.classList.remove("input-error");
        });
    }
  
    _serializeForm(modal) {
        const data = {};
        const checkboxValues = {};

        modal.querySelectorAll("input,textarea,select").forEach((el) => {
            const raw = el.dataset.field || el.name || el.id || "";
            if (!raw) return;
            
            let key = raw.replace(/^detail/, "");
            key = key.charAt(0).toLowerCase() + key.slice(1);
            key = key.replace(/Input$/, "");
            
            const type = el.type ? el.type.toLowerCase() : "";
            
            if (type === 'checkbox') {
                if (el.checked) {
                    if (!checkboxValues[key]) checkboxValues[key] = [];
                    checkboxValues[key].push(el.value);
                }
            } else if (type === 'radio') {
                if (el.checked) {
                    data[key] = el.value;
                }
            } else {
                data[key] = el.value;
            }
        });

        Object.keys(checkboxValues).forEach(key => {
            data[key] = checkboxValues[key].join(', '); 
        });
        
        return data;
    }
  
    async saveData() {
        if (!this._validateRequired(this.config.modalId)) return;
        const modal = document.querySelector(this.config.modalId);
        const data = this._serializeForm(modal); 
        
        CommonLoading?.show?.();
        try {
            const res = await fetch(this.config.apiUrl, this._opts("POST", data));
            if (!res.ok) throw new Error("등록 실패");
            Toast?.show?.("등록 완료", "success");
            this.closeAllModals();
            this._clientData = null;
            await this.loadList(this.currentPage, "web", this.lastSearch);
        } catch (e) {
            console.error(e);
            Toast?.show?.("등록 실패", "error");
        } finally {
            CommonLoading?.hide?.();
            document.body.classList.remove("modal-open");
            this._hideGlobalOverlay();
        }
    }

    async updateData(id) {
        if (!this._validateRequired(this.config.detailModalId)) return;
        const modal = document.querySelector(this.config.detailModalId);
        const data = this._serializeForm(modal);
        
        CommonLoading?.show?.();
        try {
            const res = await fetch(
                `${this.config.apiUrl}/${id}`,
                this._opts("PUT", data)
            );
            if (!res.ok) throw new Error("수정 실패");
            Toast?.show?.("수정 완료", "success");
            this.closeAllModals();
            this._clientData = null;
            await this.loadList(this.currentPage, "web", this.lastSearch);
        } catch (e) {
            console.error(e);
            Toast?.show?.("수정 실패", "error");
        } finally {
            CommonLoading?.hide?.();
            document.body.classList.remove("modal-open");
            this._hideGlobalOverlay();
        }
    }

    async deleteSelected() {
        const ids = Array.from(
            document.querySelectorAll(
                `${this.config.tableBodySelector} .row-checkbox:checked`
            )
        ).map((cb) => parseInt(cb.dataset.id));
        if (!ids.length)
            return Toast?.show?.("삭제할 항목을 선택하세요", "warning");
        if (!confirm(`${ids.length}개 항목을 삭제하시겠습니까?`)) return;

        CommonLoading?.show?.();
        try {
            const res = await fetch(this.config.apiUrl, this._opts("DELETE", ids));
            if (!res.ok) throw new Error("삭제 실패");
            Toast?.show?.("삭제 완료", "success");
            this._clientData = null;
            await this.loadList(this.currentPage, "web", this.lastSearch);
        } catch (e) {
            console.error(e);
            Toast?.show?.("삭제 실패", "error");
        } finally {
            CommonLoading?.hide?.();
            document.body.classList.remove("modal-open");
            this._hideGlobalOverlay();
        }
    }

    async downloadExcel() {
        const csrfToken = this.csrfToken;
        const csrfHeader = this.csrfHeader;
        const search =
            document.querySelector(this.config.searchInputSelector)?.value ||
            this.lastSearch || "";

        const baseUrl = this.config.apiUrl.replace(/\/list$/, "");
        const url = `${baseUrl}/excel?search=${encodeURIComponent(search)}&t=${Date.now()}`;

        let overlay = document.querySelector(this.config.globalLoadingSelector || "#globalLoading");
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

        try {
            const headers = {};
            if (csrfHeader && csrfToken) headers[csrfHeader] = csrfToken;
            const res = await fetch(url, { method: "GET", headers });
            if (!res.ok) throw new Error("엑셀 다운로드 실패");

            const disposition = res.headers.get("Content-Disposition");
            let filename = this.config.excelFileName || "리스트.xlsx";
            if (disposition) {
                const utf8 = disposition.match(/filename\*=UTF-8''(.+)/);
                const ascii = disposition.match(/filename="(.+)"/);
                if (utf8) filename = decodeURIComponent(utf8[1]);
                else if (ascii) filename = ascii[1];
            }

            const blob = await res.blob();
            const blobUrl = window.URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = blobUrl;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(blobUrl);
            Toast?.show?.(`${filename} 다운로드 완료`, "success");
        } catch (err) {
            console.error(err);
            alert("엑셀 다운로드 오류");
        } finally {
            this._hideGlobalOverlay();
        }
    }

    _validateRequired(containerSel) {
        const container = document.querySelector(containerSel);
        if (!container) return true;
        const requiredCols = (this.config.columns || []).filter((c) => c.required);
        for (const col of requiredCols) {
            const sel = `#${col.key}, #${col.key}Input, #detail${col.key
                .charAt(0)
                .toUpperCase()}${col.key.slice(1)}`;
            const el = container.querySelector(sel);
            const val = el ? String(el.value ?? "").trim() : "";
            if (!val) {
                el?.classList.add("input-error");
                el?.focus();
                Toast?.show?.(`'${col.label}'은(는) 필수 입력 항목입니다.`, "warning");
                return false;
            }
            el.classList.remove("input-error");
        }
        return true;
    }

    _showModalLoading(modal) {
        let overlay = modal.querySelector(".modal-loading");
        if (!overlay) {
            overlay = document.createElement("div");
            overlay.className = "modal-loading";
            overlay.innerHTML = `<div class="spinner"></div>`;
            modal.appendChild(overlay);
        }
        overlay.style.display = "flex";
    }

    _hideModalLoading(modal) {
        const overlay = modal.querySelector(".modal-loading");
        if (overlay) overlay.style.display = "none";
    }

    _hideGlobalOverlay() {
        document.querySelectorAll(".global-loading-overlay, #globalLoading").forEach((ov) => {
            ov.style.display = "none";
            ov.style.pointerEvents = "none";
        });
    }

    _opts(method, body = null) {
        const headers = { "Content-Type": "application/json" };
        if (this.csrfHeader && this.csrfToken)
            headers[this.csrfHeader] = this.csrfToken;
        return { method, headers, body: body ? JSON.stringify(body) : undefined };
    }
}

/* ==========================================================
   ✅ 반응형 페이징 (원본 구조 유지)
========================================================== */
function getPageGroupSize() {
    const w = window.innerWidth;
    if (w < 480) return 3;
    if (w < 768) return 5;
    if (w < 1024) return 10;
    return 20;
}

let pageGroupSize = getPageGroupSize();

window.addEventListener("resize", () => {
    const newSize = getPageGroupSize();
    if (newSize !== pageGroupSize) {
        pageGroupSize = newSize;
        const inst = window.unifiedListInstance;
        if (!inst) return;

        const totalPages =
            (inst._lastTotalPages || inst._clientData?.length / inst.pageSize) || 1;

        renderPagination(
            inst.currentPage,
            Math.ceil(totalPages),
            inst.config.paginationSelector,
            inst.loadList.bind(inst),
            pageGroupSize
        );
    }
});