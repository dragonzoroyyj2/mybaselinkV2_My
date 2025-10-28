/**
 * 🧩 commonPagination_op.js
 * --------------------------------------------------------
 * ✅ 공용 페이징 버튼 렌더링
 * --------------------------------------------------------
 */

function renderPagination(currentPage, totalPages, containerSelector, onPageClick, pageGroupSize = 5) {
  const container = document.querySelector(containerSelector);
  if (!container) return;
  container.innerHTML = "";
  if (totalPages <= 0) return;

  // 화면 크기에 따라 groupSize 동적 조정
  let groupSize = pageGroupSize;
  const tbody = document.querySelector("tbody");
  if (tbody) {
    const containerWidth = tbody.offsetWidth;
    const approxBtnWidth = 36;
    let maxBtnPerRow = Math.floor(containerWidth / approxBtnWidth);
    if (window.innerWidth <= 768) maxBtnPerRow = Math.min(maxBtnPerRow, 5);
    groupSize = Math.min(groupSize, maxBtnPerRow, totalPages);
    if (groupSize < 1) groupSize = 1;
  }

  const currentGroup = Math.floor(currentPage / groupSize);
  const startPage = currentGroup * groupSize;
  const endPage = Math.min(startPage + groupSize, totalPages);

  const makeBtn = (text, disabled, click) => {
    const btn = document.createElement("button");
    btn.textContent = text;
    btn.disabled = disabled;
    if (!disabled && typeof click === "function") btn.addEventListener("click", click);
    container.appendChild(btn);
  };

  makeBtn("<<", currentPage === 0, () => onPageClick(0));
  makeBtn("<", currentPage === 0, () => onPageClick(currentPage - 1));

  for (let i = startPage; i < endPage; i++) {
    const btn = document.createElement("button");
    btn.textContent = i + 1;
    if (i === currentPage) btn.classList.add("active");
    btn.addEventListener("click", () => onPageClick(i));
    container.appendChild(btn);
  }

  makeBtn(">", currentPage >= totalPages - 1, () => onPageClick(currentPage + 1));
  makeBtn(">>", currentPage >= totalPages - 1, () => onPageClick(totalPages - 1));

  try {
    container.style.maxWidth = document.querySelector("tbody")?.offsetWidth + "px";
  } catch (e) { /* ignore */ }
}
