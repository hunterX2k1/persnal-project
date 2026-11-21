; Inno Setup script -> builds MemExplorerSetup.exe (an installer) around the exe.
; Prereq: build dist\memexplorer.exe first (packaging\build_exe.bat), then open
; this file in Inno Setup (https://jrsoftware.org/isdl.php) and click Compile,
; or run:  iscc packaging\installer.iss   from the repo root.

#define AppName "Memory Explorer"
#define AppVer  "0.1.0"
#define AppExe  "memexplorer.exe"

[Setup]
AppName={#AppName}
AppVersion={#AppVer}
DefaultDirName={autopf}\MemExplorer
DefaultGroupName={#AppName}
OutputDir=dist
OutputBaseFilename=MemExplorerSetup
Compression=lzma2
SolidCompression=yes
; memory inspection of other processes needs an elevated token
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md";      DestDir: "{app}"; Flags: ignoreversion isreadme
Source: "..\docs\*";         DestDir: "{app}\docs"; Flags: recursesubdirs ignoreversion

[Tasks]
Name: "addtopath"; Description: "Add MemExplorer to PATH (use 'memexplorer' in any terminal)"; GogroupDescription: "Options"; Flags: unchecked

[Registry]
; Append install dir to the system PATH when the task is selected
Root: HKLM; Subkey: "SYSTEM\CurrentControlSet\Control\Session Manager\Environment"; \
    ValueType: expandsz; ValueName: "Path"; ValueData: "{olddata};{app}"; \
    Tasks: addtopath; Check: NeedsAddPath('{app}')

[Icons]
Name: "{group}\Memory Explorer (console)"; Filename: "cmd.exe"; \
    Parameters: "/k ""cd /d {app} && {#AppExe} --help"""; WorkingDir: "{app}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"

[Code]
function NeedsAddPath(Param: string): Boolean;
var Path: string;
begin
  if not RegQueryStringValue(HKLM,
    'SYSTEM\CurrentControlSet\Control\Session Manager\Environment', 'Path', Path) then
  begin Result := True; exit; end;
  Result := Pos(';' + ExpandConstant(Param) + ';', ';' + Path + ';') = 0;
end;
