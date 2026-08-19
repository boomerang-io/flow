/* eslint-disable no-template-curly-in-string */
import React, { useRef, useState, useEffect } from "react";
import PropTypes from "prop-types";
import CodeMirror from "codemirror";
import { Controlled as CodeMirrorReact } from "react-codemirror2";
import {
  ModalBody,
  ModalFooter,
  Search,
  Dropdown,
  Button,
} from "@carbon/react";
import { Undo, Redo, Copy, Cut, Paste, ArrowUp, ArrowDown } from "@carbon/react/icons";
import "codemirror/addon/comment/comment.js";
import "codemirror/addon/fold/brace-fold.js";
import "codemirror/addon/fold/comment-fold.js";
import "codemirror/addon/fold/foldcode.js";
import "codemirror/addon/fold/foldgutter.js";
import "codemirror/addon/fold/indent-fold.js";
import "codemirror/addon/hint/javascript-hint";
import "codemirror/addon/hint/show-hint";
import "codemirror/addon/search/searchcursor";
import "codemirror/mode/javascript/javascript";
import "codemirror/mode/markdown/markdown";
import "codemirror/mode/python/python";
import "codemirror/mode/shell/shell";
import "codemirror/mode/yaml/yaml";

TextEditorView.propTypes = {
  closeModal: PropTypes.func.isRequired,
  isLanguageSelectorDisabled: PropTypes.bool,
  item: PropTypes.shape({
    name: PropTypes.string,
  }),
  setTextAreaValue: PropTypes.func.isRequired,
  setShouldConfirmModalClose: PropTypes.func,
  value: PropTypes.string,
};

const escapeRegExp = (val) => {
  return val && val.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
};

const languages = [
  {
    id: "javascript",
    text: "JavaScript/JSON",
    params: { hint: CodeMirror.hint.javascript, mode: { name: "javascript" } },
  },
  { id: "markdown", text: "Markdown", params: { mode: "markdown" } },
  { id: "python", text: "Python", params: { mode: "python" } },
  { id: "shell", text: "Shell", params: { mode: "shell" } },
  { id: "text", text: "Text", params: { mode: "text/plain" } },
  { id: "yaml", text: "YAML", params: { mode: "yaml" } },
];

function TextEditorView(props) {
  const [value, setValue] = useState(props.value);
  const editor = useRef(null);
  const [doc, setDoc] = useState();
  const [searchText, setSearchText] = useState("");
  const [clipboard, setClipboard] = useState("");
  const [languageParams, setLanguageParams] = useState(
    props.language
      ? languages.find((value) => value.id === props.language).params
      : { id: "text", text: "Text", params: { mode: "text/plain" } }
  );

  useEffect(() => {
    const autoSuggestions =
      props.autoSuggestions?.map((elm) => {
        return { text: elm.value, displayText: elm.label };
      }) ?? [];

    CodeMirror.registerHelper("hint", "dictionaryHint", function (editor) {
      const cur = editor.getCursor();
      const curLine = editor.getLine(cur.line);
      let start = cur.ch;
      let end = start;
      while (end < curLine.length && /[\w${:]/.test(curLine.charAt(end))) ++end;
      while (start && /[\w${:]/.test(curLine.charAt(start - 1))) --start;
      const curWord = start !== end && curLine.slice(start, end);
      const regex = new RegExp("^" + escapeRegExp(curWord), "i");
      return {
        list: (!curWord
          ? []
          : autoSuggestions.filter(
              (item) => (curWord.startsWith("$(") && item.text.match(regex)) || item.displayText.match(regex)
            )
        ).sort(),
        from: CodeMirror.Pos(cur.line, start),
        to: CodeMirror.Pos(cur.line, end),
      };
    });
  }, [props.autoSuggestions]);

  const saveValue = () => {
    props.setTextAreaValue(value);
    props.formikSetFieldValue(value);
    props.closeModal();
  };

  const undo = () => {
    doc.undo();
  };

  const redo = () => {
    doc.redo();
  };

  const cut = () => {
    setClipboard(doc.getSelection());
    doc.replaceSelection("");
  };

  const copy = () => {
    setClipboard(doc.getSelection());
  };

  const paste = () => {
    doc.replaceSelection(clipboard);
  };

  const handleKeyPress = (e) => {
    if (e.key === "Enter") {
      findNext();
    }
  };

  const handleSearchText = (e) => {
    const { value } = e.target;
    setSearchText(value);
  };

  const findNext = () => {
    const cursor = doc.getSearchCursor(searchText, doc.getCursor());
    if (cursor.findNext()) {
      doc.setCursor(cursor.to());
      doc.setSelection(cursor.from(), cursor.to());
    }
    editor.current.focus();
  };

  const findPrevious = () => {
    const cursor = doc.getSearchCursor(searchText, doc.getCursor("start"));
    if (cursor.findPrevious()) {
      doc.setCursor(cursor.to());
      doc.setSelection(cursor.from(), cursor.to());
    }
    editor.current.focus();
  };

  const languageOptions = languages.map((language) => ({ id: language.id, text: language.text }));

  const onChangeLanguage = (language) => {
    setLanguageParams(languages.find((value) => value.id === language.selectedItem.id).params);
  };

  //TB trying to get autocomplete to work
  const autoComplete = (cm) => {
    CodeMirror.showHint(cm, CodeMirror.hint.dictionaryHint, { completeSingle: false });
  };

  const foldCode = (cm) => {
    cm.foldCode(cm.getCursor());
  };

  const toggleComment = (cm) => {
    cm.toggleComment();
  };

  const blockComment = (cm) => {
    if (doc.somethingSelected()) {
      const selPosition = doc.listSelections();
      if (!cm.uncomment(selPosition[0].head, selPosition[0].anchor, { fullLines: false })) {
        cm.blockComment(selPosition[0].head, selPosition[0].anchor, { fullLines: false });
      }
    }
  };

  return (
    <>
      <ModalBody className="c-textEditorContainer">
        <div className="b-task-text-area">
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Undo"
            tooltipPosition="bottom"
            tooltipAlignment="start"
            renderIcon={Undo}
            onClick={undo}
            className="b-task-text-area__button"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Redo"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={Redo}
            onClick={redo}
            className="b-task-text-area__button"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Copy"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={Copy}
            onClick={copy}
            className="b-task-text-area__button"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Cut"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={Cut}
            onClick={cut}
            className="b-task-text-area__button"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Paste"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={Paste}
            onClick={paste}
            className="b-task-text-area__button"
          />
          <Search
            id="search"
            light={false}
            labelText="Search"
            closeButtonLabelText=""
            placeholder="Search"
            onChange={handleSearchText}
            onKeyPress={handleKeyPress}
            size="sm"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Find previous"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={ArrowUp}
            onClick={findPrevious}
            className="b-task-text-area__button"
          />
          <Button
            hasIconOnly
            size="sm"
            kind="ghost"
            iconDescription="Find next"
            tooltipPosition="bottom"
            tooltipAlignment="center"
            renderIcon={ArrowDown}
            onClick={findNext}
            className="b-task-text-area__button"
          />
          {!props.isLanguageSelectorDisabled && (
            <div className="b-task-text-area__language-dropdown">
              <Dropdown
                id="dropdown-language"
                type="default"
                label="Language selection"
                ariaLabel="Dropdown"
                light={false}
                initialSelectedItem={
                  props.language
                    ? languageOptions.find((languageOption) => languageOption.id === props.language)
                    : languageOptions[0]
                }
                items={languageOptions}
                itemToString={(item) => (item ? item.text : "")}
                onChange={onChangeLanguage}
              />
            </div>
          )}
        </div>
        <CodeMirrorReact
          editorDidMount={(cmeditor) => {
            editor.current = cmeditor;
            setDoc(cmeditor.getDoc());
          }}
          value={value}
          options={{
            readOnly: props.readOnly,
            theme: "material",
            extraKeys: {
              "Ctrl-Space": "autocomplete",
              "Ctrl-Q": foldCode,
              "Cmd-/": toggleComment,
              "Shift-Alt-A": blockComment,
              "Shift-Opt-A": blockComment,
            },
            lineWrapping: true,
            foldGutter: true,
            lineNumbers: true,
            gutters: ["CodeMirrorReact-linenumbers", "CodeMirror-foldgutter"],
            ...languageParams,
          }}
          onBeforeChange={(editor, data, value) => {
            setValue(value);
          }}
          //TB: trying to get autocomplete to work
          onKeyUp={(cm, event) => {
            if (
              !cm.state.completionActive /*Enables keyboard navigation in autocomplete list*/ &&
              event.keyCode !== 13
            ) {
              /*Enter - do not open autocomplete list just after item has been selected in it*/
              autoComplete(cm);
            }
          }}
        />
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" onClick={props.closeModal}>
          Cancel
        </Button>
        <Button onClick={saveValue}>Update</Button>
      </ModalFooter>
    </>
  );
}

export default TextEditorView;
