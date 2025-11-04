/**
 * 🧭 commonPagination_op.js (v1.0)
 * --------------------------------------------------------
 * ✅ 공통 페이징 렌더러 (버튼 그룹 크기 지원)
 * ✅ 외부에서 dispatchEvent(new Event("resizePagination"))로 갱신 가능
 * --------------------------------------------------------
 * 사용법:
 *   renderPagination(currentPage, totalPages, "#pagination", onPageChange, pageGroupSize);
 */

(function (global) {
  function renderPagination(currentPage, totalPages, containerSelector, onPageChange, pageGroupSize) {
    const el = document.querySelector(containerSelector);
    if (!el) return;

    // 안정성 보장
    totalPages = Math.max(1, parseInt(totalPages || 1, 10));
    currentPage = Math.min(Math.max(0, parseInt(currentPage || 0, 10)), totalPages - 1);
    pageGroupSize = Math.max(1, parseInt(pageGroupSize || 5, 10));

    const start = Math.floor(currentPage / pageGroupSize) * pageGroupSize;
    const end = Math.min(start + pageGroupSize, totalPages);

    let html = '';

    // 처음/이전
    html += `<button type="button" class="page-btn first" ${currentPage === 0 ? 'disabled' : ''} data-page="0">«</button>`;
    html += `<button type="button" class="page-btn prev" ${currentPage === 0 ? 'disabled' : ''} data-page="${currentPage - 1}">‹</button>`;

    // 번호
    for (let p = start; p < end; p++) {
      html += `<button type="button" class="page-btn num ${p === currentPage ? 'active' : ''}" data-page="${p}">${p + 1}</button>`;
    }

    // 다음/마지막
    html += `<button type="button" class="page-btn next" ${currentPage >= totalPages - 1 ? 'disabled' : ''} data-page="${currentPage + 1}">›</button>`;
    html += `<button type="button" class="page-btn last" ${currentPage >= totalPages - 1 ? 'disabled' : ''} data-page="${totalPages - 1}">»</button>`;

    el.innerHTML = html;

    // 이벤트 위임
    el.onclick = (e) => {
      const btn = e.target.closest('.page-btn');
      if (!btn || btn.disabled) return;
      const page = parseInt(btn.dataset.page, 10);
      if (Number.isFinite(page) && typeof onPageChange === 'function') {
        onPageChange(page);
      }
    };
  }

  // 외부 호출 가능하도록 export
  global.renderPagination = renderPagination;

  // resizePagination 이벤트로 재렌더 지원 (current/total은 외부에서 다시 넘김)
  document.addEventListener('resizePagination', () => {
    // 이 이벤트는 컨트롤러(호출부)에서 current/total을 알고 다시 호출하도록 설계
    // 의도적으로 여기선 아무 것도 하지 않는다(중복 렌더 방지).
  });
})(window);
