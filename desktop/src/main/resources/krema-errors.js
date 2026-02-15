/* Placeholder for Krema IPC error helpers.
   This file is required to exist on the classpath at /krema-errors.js.
   If Krema defines additional functions here, they can be added as needed.
*/

// Provide a very defensive, no-op mapping to a string to avoid runtime failures if invoked.
(function (global) {
  function __kremaErrorToString(err) {
    try {
      if (err == null) return "null";
      if (typeof err === "string") return err;
      if (err instanceof Error && err.message) return err.message;
      if (typeof err === "object") return JSON.stringify(err);
      return String(err);
    } catch (e) {
      return "unknown error";
    }
  }

  // Expose a predictable symbol if Krema looks it up.
  global.__kremaErrorToString = __kremaErrorToString;
})(typeof globalThis !== "undefined" ? globalThis : (typeof window !== "undefined" ? window : this));
