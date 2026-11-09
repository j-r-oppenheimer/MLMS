# MLMS — 충남대 의대 LMS 안드로이드 앱

충남대 의대 LMS(cnu.u-lms.com)의 시간표·공지사항을 확인하는 비공식 안드로이드 앱

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/5194f275-b2d6-439f-bafd-ac8f466b7363" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/8197995c-6e9f-4c36-ac2e-43df19d24b03" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/0a801838-eba4-4b4c-a511-8af5f2eae80b" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/6f51e1b3-960f-4bdc-bf56-86c87a6229fd" width="250"/></td>
  </tr>
 </table>


## 다운로드

[Releases](https://github.com/j-r-oppenheimer/MLMS/releases)에서 다운로드

- **MLMS-full** — 강의자료 PPT → PDF 변환 포함 (약 60MB)
- **MLMS-lite** — 변환·파일 이름 지정 등 다운로드 부가 기능 없이 가벼운 버전 (약 3MB)

## 기능

- 시간표 조회 (주별, A/B반 필터)
- 수업 자료 다운로드
  - 다운로드 파일 이름 자동 지정 — `{날짜} {과목} ({강의명}_{교수})` 등 형식 직접 설정 (full)
  - PPTX 강의자료를 앱 안에서 바로 PDF로 변환해 저장 (full)
    - 슬라이드 크기·원본 글꼴 유지, 사진은 재압축 없이 원본 화질 그대로
    - 그림 속 글자 인식(OCR) — 이미지 속 글자도 PDF에서 검색·복사 가능
    - 없는 글꼴은 Google Fonts에서 자동 다운로드, 또는 설정에서 글꼴 파일 직접 추가
    - 맑은 고딕 등 자주 쓰는 글꼴은 PC의 `C:\Windows\Fonts`에서 `.ttf` 파일을 옮겨 등록하면 원래 글꼴 그대로 변환
- 공지사항 조회
- 시험 수업 강조 (중간·기말·MCQ·MEQ·CPX 등)
- 홈 화면 위젯 (시간표, 시험·종강 D-day)
- 테마 색 설정 및 수업 칸별 색 반전
- 자동 로그인

## 빌드

- Android Studio에서 열고 Gradle Sync 후 실행 (Gradle 9.7.1 · AGP 9.4.0을 지원하는 버전 필요)
- minSdk 26 (Android 8.0)

## 사용 기술

Kotlin · MVVM · Hilt (KSP) · Room · OkHttp · Jsoup · WorkManager · PdfBox-Android · ML Kit
