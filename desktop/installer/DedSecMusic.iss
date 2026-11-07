#ifndef AppVersion
  #define AppVersion "0.3.0"
#endif
#ifndef PayloadDir
  #define PayloadDir "..\build\compose\binaries\main\app\DedSec Music"
#endif
#ifndef OutputDirPath
  #define OutputDirPath "..\..\artifacts\desktop"
#endif

[Setup]
AppId={{F561A2F8-25CC-4A84-9D3D-753CFBB16CA0}
AppName=DedSec Music
AppVersion={#AppVersion}
AppPublisher=Dedseckms
AppPublisherURL=https://github.com/Dedseckms/Dedsec-Music
AppSupportURL=https://github.com/Dedseckms/Dedsec-Music/issues
DefaultDirName={localappdata}\Programs\DedSecMusic
DefaultGroupName=DedSec Music
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
DisableWelcomePage=no
DisableReadyPage=yes
LicenseFile=..\..\LICENSE
WizardStyle=modern dark polar includetitlebar hidebevels
WizardSizePercent=118
WizardImageFile=..\..\branding\installer-side.bmp
WizardImageStretch=yes
SetupIconFile=..\..\branding\app-icon.ico
UninstallDisplayIcon={app}\DedSec Music\DedSec Music.exe
OutputDir={#OutputDirPath}
OutputBaseFilename=DedSec-Music-{#AppVersion}-windows-x64-setup
Compression=lzma2/fast
SolidCompression=yes
LZMANumBlockThreads=2
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
UsePreviousTasks=yes

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
russian.WelcomeLabel1=Своя музыка. Своя территория.
russian.WelcomeLabel2=DedSec Music для Windows — твоя библиотека, личные плейлисты и волна под настроение.%n%nУстановим плеер со всем необходимым. Отдельные Java и VLC не нужны.
russian.FinishedHeadingLabel=Поймай свой ритм
russian.FinishedLabelNoIcons=DedSec Music установлен. Войди на свой музыкальный сервер и включай любимые треки.

[CustomMessages]
russian.StartMenuIcon=Создать ярлык в меню «Пуск»
english.StartMenuIcon=Create a Start menu shortcut

[Tasks]
Name: "startmenuicon"; Description: "{cm:StartMenuIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#PayloadDir}\*"; DestDir: "{app}\DedSec Music"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\DedSec Music"; Filename: "{app}\DedSec Music\DedSec Music.exe"; WorkingDir: "{app}\DedSec Music"; Tasks: startmenuicon; AppUserModelID: "ru.dedseckms.music.desktop"
Name: "{autodesktop}\DedSec Music"; Filename: "{app}\DedSec Music\DedSec Music.exe"; WorkingDir: "{app}\DedSec Music"; Tasks: desktopicon; AppUserModelID: "ru.dedseckms.music.desktop"

[Run]
Filename: "{app}\DedSec Music\DedSec Music.exe"; Description: "{cm:LaunchProgram,DedSec Music}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; This fixed child is the application payload. User music and accounts live outside Programs.
Type: filesandordirs; Name: "{app}\DedSec Music"
