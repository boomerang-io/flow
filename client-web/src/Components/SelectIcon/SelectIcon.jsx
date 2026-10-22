//@ts-nocheck
import { ComboBox } from "@boomerang-io/carbon-addons-boomerang-react";
import styles from "./selectIcon.module.scss";

export function SelectIcon({ onChange, selectedIcon, iconOptions }) {
  return (
    <ComboBox
      id="select-icon"
      placeholder="Choose an icon"
      labelText="Icon"
      helperText="Choose the icon that best fits this task"
      invalid={false}
      items={iconOptions.map(({ name, Icon }) => ({
        value: name,
        label: name,
        Icon: Icon,
      }))}
      itemToElement={(Item) => {
        return (
          <div className={styles.container}>
            <Item.Icon className={styles.icon} />
            {Item.label}
          </div>
        );
      }}
      initialSelectedItem={selectedIcon}
      onChange={onChange}
    />
  );
}

export default SelectIcon;
