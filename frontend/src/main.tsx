import * as ReactDOMClient from "react-dom/client";
import App from "./App";
import "./styles/input.css";
import "./styles/globals.css";
import "./styles/refinements.css";
import "./styles/match-tabs-polish.css";
import "./styles/mobile-match-fixes.css";

ReactDOMClient.createRoot(document.getElementById("root")!).render(<App/>);
