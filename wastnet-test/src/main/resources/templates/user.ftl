<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="utf-8">
    <title>wastnet FreeMarker Demo</title>
</head>
<body>
<h1>Hello ${name!'Guest'}</h1>
<ul>
<#list roles![] as role>
    <li>${role}</li>
</#list>
</ul>
</body>
</html>
