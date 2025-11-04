/**
 * 🧩 commonResponsive.js
 * --------------------------------------------------------
 * ✅ 화면 해상도별 pageGroupSize 자동 계산
 * ✅ resize 이벤트로 pageGroupSize 갱신
 * --------------------------------------------------------
 */
function getPageGroupSize() {
  const w = window.innerWidth;
  if (w < 480) return 3;
  if (w < 768) return 5;
  if (w < 1024) return 10;
  return 20;
}

function initResponsivePagination(unifiedListManager) {
  let cur = getPageGroupSize();
  window.addEventListener("resize", () => {
    const n = getPageGroupSize();
    if (n !== cur) {
      cur = n;
      unifiedListManager.pageGroupSize = n;
      document.dispatchEvent(new Event("resizePagination"));
    }
  });
}
