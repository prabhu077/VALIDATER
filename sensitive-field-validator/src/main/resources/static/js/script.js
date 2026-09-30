// Small UI helpers shared across pages. No business logic lives here --
// field detection, masking and validation are handled entirely on the server.

document.addEventListener("DOMContentLoaded", function () {
  var MAX_BYTES = 10 * 1024 * 1024; // keep in sync with spring.servlet.multipart.max-file-size

  // ---- Upload: dropzone, client-side checks, loading state -----------------
  var form = document.getElementById("csvUploadForm");
  if (form) {
    var input = document.getElementById("csvFile");
    var zone = document.getElementById("dropzone");
    var label = document.getElementById("dzFile");
    var errBox = document.getElementById("dzError");
    var submit = document.getElementById("uploadSubmitBtn");

    var fmt = function (n) {
      return n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(1) + " KB" : (n / 1048576).toFixed(1) + " MB";
    };
    var showError = function (msg) {
      errBox.textContent = msg;
      errBox.hidden = !msg;
    };
    var refresh = function () {
      var f = input.files && input.files[0];
      showError("");
      label.hidden = true;
      submit.disabled = true;
      if (!f) return;
      if (!/\.csv$/i.test(f.name)) { showError("Only .csv files are supported."); return; }
      if (f.size === 0)            { showError("That file is empty."); return; }
      if (f.size > MAX_BYTES)      { showError("That file is " + fmt(f.size) + ". The limit is 10 MB."); return; }
      label.textContent = "📄 " + f.name + " · " + fmt(f.size);
      label.hidden = false;
      submit.disabled = false;
    };

    input.addEventListener("change", refresh);

    ["dragenter", "dragover"].forEach(function (evt) {
      zone.addEventListener(evt, function (e) { e.preventDefault(); zone.classList.add("dragover"); });
    });
    ["dragleave", "drop"].forEach(function (evt) {
      zone.addEventListener(evt, function (e) { e.preventDefault(); zone.classList.remove("dragover"); });
    });
    zone.addEventListener("drop", function (e) {
      if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) {
        input.files = e.dataTransfer.files;
        refresh();
      }
    });

    form.addEventListener("submit", function (e) {
      if (submit.disabled) { e.preventDefault(); return; }
      document.getElementById("uploadSpinner").classList.add("active");
      document.getElementById("uploadLoadingText").classList.add("active");
      submit.disabled = true;
      submit.textContent = "Scanning…";
    });
  }

  // ---- Review table: search + filter chips ---------------------------------
  var table = document.getElementById("reviewTable");
  if (table) {
    var rows = Array.prototype.slice.call(table.tBodies[0].rows);
    var search = document.getElementById("colSearch");
    var chips = document.querySelectorAll(".chips .chip[data-filter]");
    var empty = document.getElementById("noMatches");
    var filter = "all";

    var apply = function () {
      var q = (search.value || "").trim().toLowerCase();
      var shown = 0;
      rows.forEach(function (tr) {
        var sensitive = tr.getAttribute("data-sensitive") === "true";
        var review = tr.getAttribute("data-review") === "true";
        var okFilter =
          filter === "all" ||
          (filter === "sensitive" && sensitive) ||
          (filter === "clean" && !sensitive) ||
          (filter === "review" && review);
        var okText = !q || tr.getAttribute("data-name").indexOf(q) !== -1;
        var show = okFilter && okText;
        tr.hidden = !show;
        if (show) shown++;
      });
      empty.hidden = shown !== 0;
    };

    search.addEventListener("input", apply);
    chips.forEach(function (chip) {
      chip.addEventListener("click", function () {
        chips.forEach(function (c) { c.classList.remove("active"); });
        chip.classList.add("active");
        filter = chip.getAttribute("data-filter");
        apply();
      });
    });
  }

  // ---- Payment showcase: highlight the selected plan card ------------------
  document.querySelectorAll('input[name="plan"]').forEach(function (input) {
    input.addEventListener("change", function () {
      document.querySelectorAll(".plan-option").forEach(function (el) { el.style.borderColor = ""; });
      input.closest(".plan-option").style.borderColor = "#2f5fdd";
    });
  });
});
