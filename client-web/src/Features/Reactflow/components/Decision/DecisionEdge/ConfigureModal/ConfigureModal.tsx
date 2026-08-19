import React from "react";
import { Button, CodeSnippet, ModalBody, ModalFooter } from "@carbon/react";
import { ModalFlowForm, TextArea, Toggle } from "@boomerang-io/carbon-addons-boomerang-react";
import "./styles.scss";

interface ConfigureDecisionModalProps {
  closeModal: () => void;
  decisionCondition?: string;
  onUpdate: (decisionCondition: string) => void;
}

function ConfigureDecisionModal(props: ConfigureDecisionModalProps) {
  // If the decisionCondition value is falsy, then default state is true
  const [defaultState, setDefaultState] = React.useState(!Boolean(props.decisionCondition));
  const [switchCondition, setSwitchCondition] = React.useState(props.decisionCondition ?? "");

  const updateSwitchDecision = (newSwitchDecision: string) => {
    setSwitchCondition(newSwitchDecision);
  };

  const updateDefaultState = () => {
    setDefaultState(!defaultState);
  };

  // If they select default, then send through empty string
  // This preserves the switchCondition if they toggle the default
  const handleOnUpdate = (e: any) => {
    e.preventDefault();
    props.onUpdate(defaultState ? "" : switchCondition);
    props.closeModal();
  };

  return (
    <ModalFlowForm onSubmit={handleOnUpdate}>
      <ModalBody>
        <Toggle
          aria-labelledby="toggle-default"
          id="default"
          name="default"
          orientation="vertical"
          toggled={defaultState}
          labelText="Default"
          helperText="This path will be taken when no other switch path is matched.."
          onToggle={updateDefaultState}
        />

        <div className="b-switch-customvalue">
          {!defaultState && (
            <>
              <TextArea
                id="parameter"
                invalid={!switchCondition}
                invalidText="Value is required"
                labelText="Switch Parameter Value"
                name="parameter"
                placeholder="Enter a value"
                onChange={(e) => updateSwitchDecision(e.target.value)}
                style={{ resize: "none" }}
                value={switchCondition}
              />
              <div className="s-switch-customvalue-desc">
                Enter the value(s) to match to take this path. Multiple values can be entered, one per line. Only one
                must match for this connection to be valid.
              </div>
              <section>
                <p className="s-switch-customvalue-tips-header">Tips:</p>
                <div className="s-switch-customvalue-wildcard-section">
                  <CodeSnippet type="inline" hideCopyButton>
                    .*
                  </CodeSnippet>
                  <p className="s-switch-customvalue-wildcard">can be used as a wildcard.</p>
                </div>
                <div className="s-switch-customvalue-wildcard-section">
                  <CodeSnippet type="inline" hideCopyButton>
                    \w
                  </CodeSnippet>
                  <p className="s-switch-customvalue-wildcard">can be used as a wildcard of any word character.</p>
                </div>
              </section>
            </>
          )}
        </div>
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={props.closeModal}>
          Cancel
        </Button>
        <Button disabled={!defaultState && !switchCondition} type="submit">
          Save
        </Button>
      </ModalFooter>
    </ModalFlowForm>
  );
}

export default ConfigureDecisionModal;
