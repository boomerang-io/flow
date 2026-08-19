import { useRef, useState } from "react";
import useMutationObserver from "./useMutationObserver";

const htmlElem = document.getElementsByTagName("html")[0];
const HTML_MODAL_CLASS = "cds--bmrg-html-modal-is-open";

function useIsModalOpen() {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const htmlRef = useRef(htmlElem);

  useMutationObserver(
    htmlRef,
    (mutationRecords) => {
      const record = mutationRecords.find((record: any) => record.type === "attributes");

      //@ts-ignore
      if (record?.target.className === HTML_MODAL_CLASS) {
        setIsModalOpen(true);
      } else {
        setIsModalOpen(false);
      }
    },
    {
      attributes: true,
      characterData: false,
      subtree: false,
      childList: false,
    }
  );

  return isModalOpen;
}

export default useIsModalOpen;
