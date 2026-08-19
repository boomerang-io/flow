import React, { useState } from "react";
import { ChevronRight, DragHorizontal, RequestQuote } from "@carbon/react/icons";
import "Styles/markdown.css";
import cx from "classnames";
import ReactMarkdown from "react-markdown";
import ReactMde from "react-mde";
import "react-mde/lib/styles/css/react-mde-all.css";
import { ObjectValues } from "Types";
import styles from "./Notes.module.scss";

interface NotesProps {
  markdown?: string;
  updateNotes: (markdown: string) => void;
}

const mdeToolbarCommands = [
  ["header", "bold", "italic", "strikethrough"],
  ["link", "quote", "code"],
  ["unordered-list", "ordered-list", "checked-list"],
];

const MDETabs = {
  Preview: "preview",
  Write: "write",
} as const;

// window resize values
const defaultWidth = 640;
const maxWidth = window.innerWidth - 300;
const minWidth = 400;

function Notes({ markdown, updateNotes }: NotesProps) {
  const [selectedTab, setSelectedTab] = useState<ObjectValues<typeof MDETabs>>(MDETabs.Preview);
  const [isSidenavOpen, setIsSidenavOpen] = useState(false);

  // controls for window resize
  const [totalWidth, setTotalWidth] = useState(defaultWidth);
  let startMousePositionX: any;

  const stopResize = () => {
    window.removeEventListener("mousemove", resize, false);
    window.removeEventListener("mouseup", stopResize, false);
  };

  const resize = (event: any) => {
    const mousePositionX = event.clientX;
    const increment = mousePositionX - startMousePositionX;
    let newWidth = totalWidth - increment;

    if (increment) {
      if (newWidth < minWidth) {
        newWidth = minWidth;
      } else if (newWidth > maxWidth) {
        newWidth = maxWidth;
      }
      setTotalWidth(newWidth);
    }
  };

  const startResize = async (event: any) => {
    startMousePositionX = event.clientX;
    window.addEventListener("mousemove", resize, false);
    window.addEventListener("mouseup", stopResize, false);
  };

  const resetNotesContainer = () => {
    setTotalWidth(defaultWidth);
    setIsSidenavOpen(!isSidenavOpen);
  };

  const handleChangeMarkdown = (markdown: string) => {
    updateNotes(markdown);
  };

  return (
    <aside
      id="notes_container"
      className={cx(styles.container, { [styles.collapsed]: !isSidenavOpen })}
      style={{ width: `${totalWidth / 16}rem` }}
    >
      <button className={styles.collapseButton} onClick={resetNotesContainer}>
        <ChevronRight className={styles.chevron} />
        <RequestQuote className={styles.notesIcon} />
      </button>
      <button
        disabled={!isSidenavOpen}
        className={styles.resizerContainer}
        onMouseDown={isSidenavOpen ? startResize : () => false}
      >
        {isSidenavOpen && <DragHorizontal className={styles.dragIcon} />}
      </button>
      <div className={styles.notesContainer}>
        <h1 className={styles.notesHeading}>Notes</h1>
        <ReactMde
          value={markdown}
          onChange={handleChangeMarkdown}
          selectedTab={selectedTab}
          onTabChange={setSelectedTab}
          toolbarCommands={mdeToolbarCommands}
          classes={{
            reactMde: styles.reactMde,
            textArea: styles.reactMdeTextArea,
            preview: styles.reactMdePreview,
          }}
          childProps={{
            writeButton: {
              className: cx(styles.tabButton, { [styles.selected]: selectedTab === MDETabs.Write }),
            },
            previewButton: {
              className: cx(styles.tabButton, { [styles.selected]: selectedTab === MDETabs.Preview }),
            },
          }}
          generateMarkdownPreview={(markdown: string) =>
            Promise.resolve(<ReactMarkdown className="markdown-body" children={markdown} />)
          }
        />
      </div>
    </aside>
  );
}

export default Notes;
