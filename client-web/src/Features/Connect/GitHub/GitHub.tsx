import React, { useEffect } from "react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { Helmet } from "react-helmet";
import { useMutation } from "react-query";
import { useHistory } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { resolver } from "Config/servicesConfig";
import styles from "./github.module.scss";

interface GitHubProps {
  installId: string;
  team: string;
}

export default function Github({ installId, team }: GitHubProps) {
  const history = useHistory();

  const postGitHubAppLinkMutation = useMutation(resolver.postGitHubAppLink);

  useEffect(() => {
    const handleLink = async () => {
      const requestBody = {
        team: team,
        ref: installId,
      };
      try {
        await postGitHubAppLinkMutation.mutateAsync({ body: requestBody });
        notify(
          <ToastNotification
            kind="success"
            title="Link Successful"
            subtitle={`${team} successfully linked to GitHub`}
          />,
        );
        setTimeout(() => {
          history.push({
            pathname: appLink.integrations({ team: team }),
          });
        }, 1000); // 1000ms = 1 seconds
      } catch (error) {
        notify(
          <ToastNotification kind="error" title="Something's Wrong" subtitle="Request to link GitHub App failed." />,
        );
      }
    };

    handleLink();
  }, [installId, team, history]);

  return (
    <section aria-label="User Settings" className={styles.settingsContainer}>
      <Helmet>
        <title>Integration Connect</title>
      </Helmet>
      <section className={styles.container}>
        <IsometricLoadingStaticIllustration />
        <h1>GitHub App Integration</h1>
        <p>Thank you for installing the GitHub App!</p>
        <br />
        <p>Last step - we are connecting the GitHub App to your team ({team}).</p>
        <br />
        <p>This page will redirect when complete</p>
      </section>
    </section>
  );
}

export function IsometricLoadingStaticIllustration() {
  return (
    <svg
      width="300"
      height="240"
      viewBox="0 0 300 240"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className="mx-auto"
    >
      {/* Base/Floor */}
      <path d="M40 180L150 120L260 180L150 240L40 180Z" fill="#F4F4F4" stroke="#E0E0E0" strokeWidth="1" />

      {/* Loading spinner (outer ring) */}
      <circle
        cx="190"
        cy="130"
        r="25"
        stroke="#8D8D8D"
        strokeWidth="3"
        strokeDasharray="5 3"
        className="loading-spinner"
      />

      {/* Loading spinner (inner progress) - will be animated with CSS */}
      <path
        d="M190 105 A25 25 0 0 1 215 130"
        stroke="#0F62FE"
        strokeWidth="3"
        strokeLinecap="round"
        className="loading-progress"
      />

      {/* Data tokens floating/moving */}
      <g className="floating-token token-1">
        <path d="M170 140L180 135L190 140L180 145L170 140Z" fill="#0F62FE" stroke="#0353E9" strokeWidth="1" />
      </g>
      <g className="floating-token token-2">
        <path d="M180 120L190 115L200 120L190 125L180 120Z" fill="#A7D4F6" stroke="#8ABBED" strokeWidth="1" />
      </g>
      <g className="floating-token token-3">
        <path d="M200 140L210 135L220 140L210 145L200 140Z" fill="#C2E2FA" stroke="#8ABBED" strokeWidth="1" />
      </g>

      {/* Small plant */}
      <path d="M70 160L80 155L90 160L80 165L70 160Z" fill="#8CD211" stroke="#78B300" strokeWidth="1" />
      <path d="M75 155L80 152.5L85 155L80 157.5L75 155Z" fill="#8CD211" stroke="#78B300" strokeWidth="1" />
      <path d="M80 165L80 150" stroke="#78B300" strokeWidth="1" />
      <path d="M70 165L80 160L90 165L80 170L70 165Z" fill="#A7A7A7" stroke="#8D8D8D" strokeWidth="1" />

      {/* Shadow on floor */}
      <path d="M150 190L190 170L230 190L190 210L150 190Z" fill="#161616" fillOpacity="0.1" />
    </svg>
  );
}
