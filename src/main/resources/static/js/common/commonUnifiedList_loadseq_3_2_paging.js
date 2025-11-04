/* ===============================================================
   ✅ commonUnifiedList_paging.js (v1.9 - 2025.11 완전 분리판)
   ---------------------------------------------------------------
   - 반응형 페이지 그룹 크기 계산
   - 창 크기 변경 시 pageGroupSize 자동 재계산
   - renderPagination() 과 통합 작동
================================================================ */

/* ----------------------------------------------------------
   🔢 페이지 그룹 크기 계산 함수
   ----------------------------------------------------------
   - 브라우저 화면폭 기준 자동 조정
   - 480px 이하: 3
   - 768px 이하: 5
   - 1024px 이하: 10
   - 그 이상: 20
---------------------------------------------------------- */
function getPageGroupSize() {
  const w = window.innerWidth;
  if (w < 480) return 3;
  if (w < 768) return 5;
  if (w < 1024) return 10;
  return 20;
}

/* ----------------------------------------------------------
   🧭 초기 계산 및 전역 변수 설정
---------------------------------------------------------- */
let pageGroupSize = getPageGroupSize();

/* ----------------------------------------------------------
   📱 리사이즈 이벤트 감지
   ----------------------------------------------------------
   - 창 크기 변경 시 자동 갱신
   - unifiedListInstance 존재 시 즉시 페이징 리렌더링
---------------------------------------------------------- */
window.addEventListener("resize", () => {
  const newSize = getPageGroupSize();
  if (newSize === pageGroupSize) return;

  pageGroupSize = newSize;

  const inst = window.unifiedListInstance;
  if (!inst) return;

  // ✅ 총 페이지 수 계산
  const totalPages =
    (inst._lastTotalPages || inst._clientData?.length / inst.pageSize) || 1;

  if (typeof renderPagination === "function") {
    renderPagination(
      inst.currentPage,
      Math.ceil(totalPages),
      inst.config.paginationSelector,
      inst.loadList.bind(inst),
      pageGroupSize
    );
  }
});

/* ----------------------------------------------------------
   🌐 UnifiedList에 전역 pageGroupSize 적용
---------------------------------------------------------- */
if (!window.pageGroupSize) {
  window.pageGroupSize = pageGroupSize;
}
