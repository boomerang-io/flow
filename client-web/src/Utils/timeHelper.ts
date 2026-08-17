export const getSimplifiedDuration = (seconds: number) => {
  const hour = 3600;
  const minute = 60;

  let result = `${Math.floor(seconds)}s`;

  if (seconds >= hour) {
    result = `${Math.floor(seconds / hour)}h`;
  } else if (seconds >= minute) {
    result = `${Math.floor(seconds / minute)}min`;
  }

  return result;
};
