<%@ include file="/include.jsp" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="context" scope="request" type="jetbrains.buildServer.controllers.parameters.ParameterRenderContext"/>
<jsp:useBean id="parameterValue" scope="request" type="java.lang.String"/>
<jsp:useBean id="parameterName" scope="request" type="java.lang.String"/>
<jsp:useBean id="maxSizeBytes" scope="request" type="java.lang.String"/>
<jsp:useBean id="allowedTypes" scope="request" type="java.lang.String"/>

<span class="fileParameterUploadControl">
  <input
    type="hidden"
    name="<c:out value='${context.id}'/>"
    id="<c:out value='${context.id}'/>"
    value="<c:out value='${parameterValue}'/>"
    data-parameter-type="file"
    data-parameter-name="<c:out value='${parameterName}'/>"
    data-max-size-bytes="<c:out value='${maxSizeBytes}'/>"
    data-allowed-types="<c:out value='${allowedTypes}'/>"
  />
  <input
    type="file"
    class="fileParameterUploadInput"
    accept="<c:out value='${allowedTypes}'/>"
    <c:if test="${context.readOnly}">disabled="disabled"</c:if>
  />
  <span class="fileParameterUploadStatus" style="margin-left:8px;"></span>
</span>

<script type="text/javascript">
  (function (root) {
    "use strict";
    if (!root || root.getAttribute("data-file-upload-initialized") === "true") return;
    root.setAttribute("data-file-upload-initialized", "true");

    var hidden = root.querySelector("input[type='hidden']");
    var fileInput = root.querySelector("input[type='file']");
    var status = root.querySelector(".fileParameterUploadStatus");
    var uploadPath = window["base_uri"] ? window["base_uri"] + "/fileParameterUpload.html" : "/fileParameterUpload.html";

    function debug(message, data) {
      var enabled = window.TeamCityFileParameterDebug === true;
      try {
        enabled = enabled || window.localStorage && window.localStorage.getItem("TeamCityFileParameterDebug") === "true";
      } catch (e) {
        enabled = enabled;
      }
      if (enabled && window.console && typeof window.console.log === "function") {
        window.console.log("[TeamCity File Parameter control] " + message, data || "");
      }
    }

    function queryParam(name) {
      var match = new RegExp("[?&]" + name + "=([^&]+)").exec(window.location.search);
      return match ? decodeURIComponent(match[1].replace(/\+/g, " ")) : "";
    }

    function decoded(value) {
      if (!value) return value;
      try {
        return decodeURIComponent(value.replace(/\+/g, "%20"));
      } catch (e) {
        return value;
      }
    }

    function buildTypeId() {
      var id = queryParam("id");
      var pathMatch = /\/buildConfiguration\/([^/?#]+)/.exec(window.location.pathname || "");
      return queryParam("buildTypeId") || queryParam("buildTypeExternalId") || (id && (id.indexOf("buildType:") === 0 || /^bt\d+$/.test(id)) ? id : "") || (pathMatch ? decoded(pathMatch[1]) : "");
    }

    function csrfToken() {
      if (window.BS && BS.CSRF && typeof BS.CSRF.getToken === "function") {
        var bsToken = BS.CSRF.getToken();
        if (bsToken) return bsToken;
      }
      if (window.BS && BS.csrfToken) return BS.csrfToken;
      var meta = document.querySelector("meta[name='tc-csrf-token']");
      if (meta) {
        var metaToken = meta.getAttribute("content");
        if (metaToken) return metaToken;
      }
      var input = document.querySelector("input[name='tc-csrf-token'], input[name='csrf_token'], input[name='csrfToken']");
      return input ? input.value : "";
    }

    function uploadUrl(token) {
      if (!token) return uploadPath;
      var separator = uploadPath.indexOf("?") >= 0 ? "&" : "?";
      return uploadPath + separator + "tc-csrf-token=" + encodeURIComponent(token);
    }

    function maxSizeBytes() {
      var value = parseInt(hidden.getAttribute("data-max-size-bytes") || "0", 10);
      return isNaN(value) || value < 0 ? 0 : value;
    }

    function allowedTypes() {
      var value = hidden.getAttribute("data-allowed-types") || "";
      if (!value) return [];
      return value.split(",").map(function (item) {
        return item.trim().toLowerCase();
      }).filter(function (item) {
        return item.length > 0;
      });
    }

    function extension(fileName) {
      var dot = fileName.lastIndexOf(".");
      return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    function mimeMatches(rule, mime) {
      if (!mime) return false;
      mime = mime.toLowerCase();
      if (rule.substring(rule.length - 2) === "/*") {
        return mime.indexOf(rule.substring(0, rule.length - 1)) === 0;
      }
      return rule === mime;
    }

    function fileTypeAllowed(file) {
      var rules = allowedTypes();
      if (rules.length === 0) return true;
      var ext = extension(file.name);
      var hasMimeRules = false;
      for (var i = 0; i < rules.length; i++) {
        var rule = rules[i];
        if (rule.charAt(0) === "." && rule === ext) return true;
        if (rule.charAt(0) !== ".") {
          hasMimeRules = true;
          if (mimeMatches(rule, file.type)) return true;
        }
      }
      return hasMimeRules;
    }

    function formatBytes(bytes) {
      if (bytes >= 1024 * 1024) return Math.round(bytes / 1024 / 1024) + " MB";
      if (bytes >= 1024) return Math.round(bytes / 1024) + " KB";
      return bytes + " B";
    }

    function uploadErrorMessage(request) {
      var contentType = (request.getResponseHeader("Content-Type") || "").toLowerCase();
      var text = request.responseText || "";
      if (contentType.indexOf("text/plain") >= 0 || contentType.indexOf("application/json") >= 0) {
        return text || "Upload failed";
      }
      return "Upload failed (HTTP " + request.status + ")";
    }

    if (!hidden || !fileInput) return;
    debug("initialized", { hiddenName: hidden.getAttribute("name"), parameterName: hidden.getAttribute("data-parameter-name"), buildTypeId: buildTypeId(), maxSizeBytes: maxSizeBytes(), allowedTypes: allowedTypes(), hasExistingValue: !!hidden.value });

    fileInput.addEventListener("change", function () {
      if (!fileInput.files || fileInput.files.length === 0) {
        hidden.value = "";
        status.textContent = "";
        debug("file cleared", { hiddenName: hidden.getAttribute("name") });
        return;
      }

      var file = fileInput.files[0];
      var maxBytes = maxSizeBytes();
      if (maxBytes > 0 && file.size > maxBytes) {
        hidden.value = "";
        status.textContent = "File is larger than " + formatBytes(maxBytes);
        debug("file rejected by browser size validation", { fileName: file.name, size: file.size, maxSizeBytes: maxBytes });
        return;
      }
      if (!fileTypeAllowed(file)) {
        hidden.value = "";
        status.textContent = "File type is not allowed";
        debug("file rejected by browser type validation", { fileName: file.name, type: file.type, allowedTypes: allowedTypes() });
        return;
      }
      var requestBuildTypeId = buildTypeId();
      var token = csrfToken();
      status.textContent = "Uploading...";
      debug("upload starting", { hiddenName: hidden.getAttribute("name"), parameterName: hidden.getAttribute("data-parameter-name"), fileName: file.name, type: file.type, size: file.size, buildTypeId: requestBuildTypeId, hasCsrfToken: !!token });

      var request = new XMLHttpRequest();
      request.open("POST", uploadUrl(token), true);
      request.setRequestHeader("X-TeamCity-File-Name", file.name);
      request.setRequestHeader("X-TeamCity-BuildType-Id", requestBuildTypeId);
      request.setRequestHeader("X-TeamCity-File-Parameter-Name", hidden.getAttribute("data-parameter-name") || "");
      if (token) {
        request.setRequestHeader("X-TC-CSRF-Token", token);
      }
      request.onreadystatechange = function () {
        if (request.readyState !== 4) return;
        if (request.status >= 200 && request.status < 300) {
          var payload = JSON.parse(request.responseText);
          hidden.value = payload.value;
          status.textContent = payload.fileName + " uploaded";
          debug("upload succeeded", { status: request.status, fileName: payload.fileName, size: payload.size });
        } else {
          hidden.value = "";
          status.textContent = uploadErrorMessage(request);
          debug("upload failed", { status: request.status, responseText: request.responseText });
        }
      };
      request.send(file);
    });
  })((document.currentScript && document.currentScript.previousElementSibling) || (function () {
    var controls = document.querySelectorAll(".fileParameterUploadControl");
    return controls.length > 0 ? controls[controls.length - 1] : null;
  })());
</script>
