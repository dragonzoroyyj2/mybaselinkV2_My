/**
 * 🧩 commonResponsive.js
 * --------------------------------------------------------
 * ✅ 화면 해상도에 따라 페이지 그룹 크기 자동 계산
 * ✅ resize 이벤트 시 자동 업데이트 지원
 * --------------------------------------------------------
 */

function getPageGroupSize() {
  const width = window.innerWidth;
  if (width < 480) return 3;
  if (width < 768) return 5;
  if (width < 1024) return 10;
  return 20;
}

/**
 * 반응형 리사이즈 이벤트 핸들러
 * unifiedListManager.pageGroupSize 값을 동적으로 조정
 */
function initResponsivePagination(unifiedListManager) {
  let currentGroupSize = getPageGroupSize();

  window.addEventListener("resize", () => {
    const newSize = getPageGroupSize();
    if (newSize !== currentGroupSize) {
      currentGroupSize = newSize;
      unifiedListManager.pageGroupSize = newSize;
      const event = new Event("resizePagination");
      document.dispatchEvent(event);
    }
  });
}
