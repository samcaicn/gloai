"use strict";
const form = document.getElementById("pairing-form");
const key = document.getElementById("owner-key");
const dialog = document.getElementById("confirm-dialog");
const revoke = document.getElementById("revoke");
key.addEventListener("invalid", () => key.setCustomValidity(key.dataset.requiredMessage));
key.addEventListener("input", () => key.setCustomValidity(""));
if (revoke && typeof dialog.showModal === "function") {
  form.addEventListener("submit", event => {
    if (event.submitter === revoke && document.getElementById("confirm-value").value !== "yes") {
      event.preventDefault();
      dialog.returnValue = "";
      dialog.showModal();
    }
  });
  dialog.addEventListener("close", () => {
    if (dialog.returnValue === "confirm") {
      document.getElementById("confirm-value").value = "yes";
      form.requestSubmit(revoke);
    } else {
      revoke.focus();
    }
  });
}
