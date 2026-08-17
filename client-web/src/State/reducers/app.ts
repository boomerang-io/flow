//@ts-nocheck
import { useReducer } from "react";
import { createContainer } from "react-tracked";

const useValue = ({ reducer, initialState }) => useReducer(reducer, initialState);

export const AppActionsTypes = {
  SetUser: "SET_USER",
  SetTeams: "SET_TEAMS",
};

export const reducer = (state, action) => {
  switch (action.type) {
    case AppActionsTypes.SetUser:
      return { ...state, user: action.data };
    case AppActionsTypes.SetTeams:
      return { ...state, teams: action.data };
    default:
      throw new Error(`unknown action type: ${action.type}`);
  }
};

export const { Provider, useTracked } = createContainer(useValue);
