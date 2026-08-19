//@ts-nocheck
import {
  AddAlt,
  Api,
  Fork,
  Chat,
  Code,
  TrashCan,
  Download,
  Edit,
  Filter,
  Idea,
  Launch,
  Power,
  Restart,
  Rocket,
  Search,
  Task,
  Terminal,
  Upload,
} from "@carbon/react/icons";

// The default "will-change: transform" prevents the icon from scaling correctly in the designer on zoom
const willChangeOverride = { willChange: "auto" };

export const taskIcons = [
  {
    name: "Add",
    Icon: ({ className = "", ...props }) => (
      <AddAlt alt="Task node type add" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "API/HTTP call",
    Icon: ({ className = "", ...props }) => (
      <Api alt="Task node type API/HTTP call" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Automated task",
    Icon: ({ className = "", ...props }) => (
      <Rocket alt="Task node type automated task" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Code",
    Icon: ({ className = "", ...props }) => (
      <Code alt="Task node type code" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Concept",
    Icon: ({ className = "", ...props }) => (
      <Idea alt="Task node type concept" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Delete",
    Icon: ({ className = "", ...props }) => (
      <TrashCan alt="Task node type delete" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Download",
    Icon: ({ className = "", ...props }) => (
      <Download alt="Task node type download" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Edit",
    Icon: ({ className = "", ...props }) => (
      <Edit alt="Task node type edit" style={willChangeOverride} className={className} {...props} />
    ),
  },

  {
    name: "Filter",
    Icon: ({ className = "", ...props }) => (
      <Filter alt="Task node type filter" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Launch",
    Icon: ({ className = "", ...props }) => (
      <Launch alt="Task node type launch" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Message",
    Icon: ({ className = "", ...props }) => (
      <Chat alt="Task node type message" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Power on/off",
    Icon: ({ className = "", ...props }) => (
      <Power alt="Task node type power on/off" style={willChangeOverride} className={className} {...props} />
    ),
  },

  {
    name: "Restart",
    Icon: ({ className = "", ...props }) => (
      <Restart alt="Task node type restart" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Search",
    Icon: ({ className = "", ...props }) => (
      <Search alt="Task node type get, read" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Switch",
    Icon: ({ className = "", ...props }) => (
      <Fork alt="Task node type switch" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Terminal",
    Icon: ({ className = "", ...props }) => (
      <Terminal alt="Task node type terminal" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Upload",
    Icon: ({ className = "", ...props }) => (
      <Upload alt="Task node type upload" style={willChangeOverride} className={className} {...props} />
    ),
  },
  {
    name: "Validate",
    Icon: ({ className = "", ...props }) => (
      <Task alt="Task node type validate" style={willChangeOverride} className={className} {...props} />
    ),
  },
];
