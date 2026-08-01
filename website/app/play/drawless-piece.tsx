import type { PieceCode } from "./chess-adapter";

const SHAPES = {
  p: "M50 14 C39 14 33 22 33 32 C33 41 38 47 44 50 C37 56 32 64 31 74 L69 74 C68 64 63 56 56 50 C62 47 67 41 67 32 C67 22 61 14 50 14 Z",
  r: "M26 15 L38 15 L38 25 L46 25 L46 15 L56 15 L56 25 L64 25 L64 15 L76 15 L73 39 L67 45 L70 74 L30 74 L33 45 L29 39 Z",
  n: "M29 74 C30 60 35 49 43 42 L36 29 L52 34 L47 18 C67 22 76 35 73 50 C71 62 62 65 64 74 Z",
  b: "M52 10 C44 17 37 28 39 38 C40 45 45 49 47 52 L43 58 C38 62 33 68 29 74 L71 74 C67 68 62 62 57 57 L53 52 C58 49 63 44 64 37 C65 27 59 17 52 10 Z",
  q: "M24 23 L35 38 L42 18 L50 38 L58 18 L65 38 L76 23 L68 54 C66 62 68 67 70 74 L30 74 C32 67 34 62 32 54 Z",
  k: "M50 25 C36 25 29 35 34 47 C37 54 40 58 36 64 L31 74 L69 74 L64 64 C60 58 63 54 66 47 C71 35 64 25 50 25 Z",
} as const;

const PALETTE = {
  white: {
    fill: "#fffcf2",
    outline: "#26332d",
    detail: "#738078",
    kingAccent: "#ad3043",
  },
  black: {
    fill: "#111a16",
    outline: "#eaf1ec",
    detail: "#9fb0a6",
    kingAccent: "#e9c349",
  },
} as const;

interface DrawlessPieceProps {
  piece: PieceCode;
  className?: string;
}

function LayeredShape({ d, fill, outline }: { d: string; fill: string; outline: string }) {
  return (
    <>
      <path d={d} fill={fill} stroke={outline} strokeWidth="7" strokeLinejoin="round" />
      <path d={d} fill={fill} stroke={outline} strokeWidth="2.4" strokeLinejoin="round" />
    </>
  );
}

/** The same original, code-native silhouettes and Imperial Marble palette used by Android. */
export function DrawlessPiece({ piece, className }: DrawlessPieceProps) {
  const type = piece.toLowerCase() as keyof typeof SHAPES;
  const colors = piece === piece.toUpperCase() ? PALETTE.white : PALETTE.black;
  const bishop = type === "b";
  const base = bishop
    ? "M25 69 L75 69 L82 88 Q83 91 77 91 L23 91 Q17 91 18 88 Z"
    : "M25 70 L75 70 L82 88 Q83 93 77 93 L23 93 Q17 93 18 88 Z";

  return (
    <svg
      className={`drawless-piece ${className ?? ""}`.trim()}
      viewBox="0 0 100 100"
      focusable="false"
      aria-hidden="true"
    >
      <LayeredShape d={SHAPES[type]} fill={colors.fill} outline={colors.outline} />

      {type === "k" ? (
        <g fill="none" strokeLinecap="butt">
          <path d="M50 5 V29 M40 14 H60" stroke={colors.outline} strokeWidth="8" />
          <path d="M50 5 V29 M40 14 H60" stroke={colors.kingAccent} strokeWidth="4.5" />
        </g>
      ) : null}

      {type === "q" ? (
        <g fill={colors.detail}>
          <circle cx="27" cy="18" r="3.4" />
          <circle cx="42" cy="13" r="3.4" />
          <circle cx="58" cy="13" r="3.4" />
          <circle cx="73" cy="18" r="3.4" />
        </g>
      ) : null}

      {type === "b" ? (
        <>
          <path d="M59 16 L47 43 Q44 47 40 43 L55 15 Z" fill={colors.outline} />
          <path d="M57 19 L43 43" stroke={colors.detail} strokeWidth="2.8" />
          <LayeredShape
            d="M27 51 L73 51 L78 60 Q80 65 73 66 L27 66 Q20 65 22 60 Z"
            fill={colors.fill}
            outline={colors.outline}
          />
          <path d="M26 59 H74" stroke={colors.detail} strokeWidth="2.8" />
        </>
      ) : null}

      {type === "n" ? (
        <>
          <circle cx="57" cy="31" r="2.6" fill={colors.detail} />
          <path d="M48 48 L63 55" stroke={colors.detail} strokeWidth="3" />
        </>
      ) : null}

      {type === "r" ? <path d="M31 42 H69" stroke={colors.detail} strokeWidth="3" /> : null}

      <LayeredShape d={base} fill={colors.fill} outline={colors.outline} />
      <path d={`M23 ${bishop ? 80 : 83} H77`} stroke={colors.detail} strokeWidth="2.6" />
    </svg>
  );
}
