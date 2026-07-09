<%@ include file="/include.jsp" %>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<tr>
  <th></th>
  <td>
    <span class="smallNote">Uploads a file from the Run Custom Build dialog and passes the temporary agent file path as the parameter value.</span>
  </td>
</tr>
<tr>
  <th><label for="maxSize">Maximum file size:</label></th>
  <td>
    <props:textProperty name="maxSize" className="textField"/>
    <span class="smallNote">Optional. Use B, KB, or MB, for example 512KB or 1MB. The global plugin limit still applies.</span>
  </td>
</tr>
<tr>
  <th><label for="allowedTypes">Allowed file types:</label></th>
  <td>
    <props:textProperty name="allowedTypes" className="textField"/>
    <span class="smallNote">Optional comma-separated list. Prefer extensions such as .csv, .pdf, .xlsx; MIME rules like text/csv or image/* are best-effort.</span>
  </td>
</tr>
