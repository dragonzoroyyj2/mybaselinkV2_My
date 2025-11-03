/**
 * ✅ commonNotify.js (중복방지형)
 * 토스트 메시지를 화면 오른쪽 상단에 표시
 */
let notifyTimeout;
let lastMsg = "";

window.notify = function(type, message) {
  if (message === lastMsg) return;
  lastMsg = message;
  clearTimeout(notifyTimeout);

  let box = document.getElementById("toastBox");
  if (!box) {
    box = document.createElement("div");
    box.id = "toastBox";
    box.style.position = "fixed";
    box.style.top = "70px";
    box.style.right = "20px";
    box.style.zIndex = "6000";
    box.style.display = "flex";
    box.style.flexDirection = "column";
    box.style.gap = "8px";
    document.body.appendChild(box);
  }

  const toast = document.createElement("div");
  toast.className = `toast ${type}`;
  toast.textContent = message;
  toast.style.cssText = `
    padding: 10px 14px;
    border-radius: 6px;
    color: #fff;
    font-weight: 500;
    min-width: 180px;
    max-width: 260px;
    box-shadow: 0 2px 6px rgba(0,0,0,0.2);
    animation: fadeInOut 3s ease-in-out forwards;
  `;

  switch (type) {
    case "success": toast.style.background = "#16a34a"; break;
    case "error": toast.style.background = "#dc2626"; break;
    case "warning": toast.style.background = "#f59e0b"; break;
    default: toast.style.background = "#2563eb";
  }

  box.appendChild(toast);

  notifyTimeout = setTimeout(() => {
    toast.remove();
    lastMsg = "";
  }, 2500);
};

const style = document.createElement("style");
style.innerHTML = `
@keyframes fadeInOut {
  0% { opacity: 0; transform: translateY(-10px); }
  10% { opacity: 1; transform: translateY(0); }
  90% { opacity: 1; }
  100% { opacity: 0; transform: translateY(-10px); }
}
`;
document.head.appendChild(style);
