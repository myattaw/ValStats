import * as ReactDOMClient from "react-dom/client";
import App from "./App";
import "./styles/input.css";
import "./styles/globals.css";
import "./styles/refinements.css";

ReactDOMClient.createRoot(document.getElementById("root")!).render(<App/>);
