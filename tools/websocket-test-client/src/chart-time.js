const KST_TIME_ZONE = 'Asia/Seoul';

const axisFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: KST_TIME_ZONE,
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const detailFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: KST_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

/** lightweight-charts의 UTC timestamp를 데이터 변경 없이 한국 시장 시각으로 표시한다. */
export function formatChartAxisTime(time) {
  return axisFormatter.format(toDate(time));
}

/** 크로스헤어의 상세 시각을 한국 시장 시각으로 표시한다. */
export function formatChartTime(time) {
  return detailFormatter.format(toDate(time));
}

function toDate(time) {
  if (typeof time !== 'number' || !Number.isFinite(time)) {
    throw new Error('차트 시간이 Unix timestamp 형식이 아닙니다.');
  }
  return new Date(time * 1000);
}
