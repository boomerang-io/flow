export const timeSecondsToTimeUnit = (seconds: number) => {
  if (!seconds) return "0 secs";
  const hoursCount = Math.floor(seconds / 3600);
  const minutesCount = Math.floor((seconds % 3600) / 60);
  const secondsCount = Math.floor(seconds % 60);

  const hoursText = hoursCount > 1 ? `${hoursCount} hrs` : hoursCount === 1 ? `1 hr` : "";
  const minutesText = minutesCount > 1 ? `${minutesCount} mins` : minutesCount === 1 ? `1 min` : "";
  const secondsText = secondsCount > 1 ? `${secondsCount} secs` : secondsCount === 1 ? `1 sec` : "";

  if (hoursText) {
    return `${hoursText} ${minutesText}`;
  }

  if (minutesText) {
    return `${minutesText} ${secondsText}`;
  }

  return secondsText;
};
