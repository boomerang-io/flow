export const stringToPassword = (str: string) => {
  return str.replace(new RegExp(".", "g"), "•");
};

/**
 * with our service-side data driven inputs. Sometimes we have "false" and "true" values returned
 * @param str
 */
export const stringToBooleanHelper = (str: string | boolean) => {
  if (str === "true" || (typeof str === "boolean" && str)) {
    return true;
  } else return false;
};
