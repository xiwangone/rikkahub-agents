import type { ComponentPropsWithRef } from "react";

export default function Logo(props: ComponentPropsWithRef<'svg'>) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 100 100"
      width="256"
      height="256"
      fill="none"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      {...props}
    >
      {/* Outer quantum orbital ring */}
      <path
        d="M50,9 A41,41 0 1,1 49.9,9"
        strokeWidth="6.5"
      />
      {/* Yao character: upper 乂 */}
      <path
        d="M63,25 C54,32 42,39 29,43 M36,25 L59,39 M64,41.5 L69,43.5"
        strokeWidth="6.8"
      />
      {/* Yao character: middle bar */}
      <path
        d="M33,49.5 L57,49.5"
        strokeWidth="6.8"
      />
      {/* Yao character: lower 乂 */}
      <path
        d="M63,49.5 C58,62 46,71 29,76 M39,55 L50,65 M55,69 L68,75"
        strokeWidth="6.8"
      />
    </svg>
  );
}
