if (!window.notify) {
  window.notify = function(type, message) {
    console.log(`[${type}] ${message}`);
    const toast = document.createElement("div");
    toast.className = `toast ${type}`;
    toast.textContent = message;
    Object.assign(toast.style, {
      position: "fixed",
      bottom: "20px",
      right: "20px",
      background: type === "error" ? "#dc2626" :
                  type === "warning" ? "#f59e0b" :
                  type === "success" ? "#16a34a" : "#1e40af",
      color: "white",
      padding: "10px 16px",
      borderRadius: "6px",
      zIndex: 9999,
      boxShadow: "0 2px 8px rgba(0,0,0,0.2)",
      opacity: "0",
      transition: "opacity 0.3s ease"
    });
    document.body.appendChild(toast);
    requestAnimationFrame(() => (toast.style.opacity = "1"));
    setTimeout(() => {
      toast.style.opacity = "0";
      setTimeout(() => toast.remove(), 300);
    }, 2500);
  };
}
