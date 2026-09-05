/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
      colors: {
        // Ground: navy-slate with real chroma, never tinted black.
        abyss:   '#0B1220',
        surface: '#111B2B',
        raised:  '#16243A',
        line:    '#223349',
        edge:    '#2E4159',
        ink:     '#E8EEF7',
        muted:   '#8A9CB4',
        faint:   '#5D708A',
        // Product signal — Postgres blue, lifted for a dark ground.
        signal: {
          DEFAULT: '#56A0F0',
          soft:    '#8FC0F7',
          deep:    '#2C6FBF',
          wash:    'rgba(86,160,240,0.10)',
        },
        // Heat ramp: colour means cost. Cool = cheap, critical = expensive.
        heat: {
          cool: '#45B39A',
          warm: '#E8B04B',
          hot:  '#F0813F',
          crit: '#F2545B',
        },
      },
      fontFamily: {
        sans: ['"Instrument Sans"', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      fontSize: {
        // Tight scale for a dense data UI.
        micro: ['11px', { lineHeight: '15px', letterSpacing: '0.005em' }],
        tiny:  ['12px', { lineHeight: '17px' }],
        base:  ['13.5px', { lineHeight: '21px' }],
        mid:   ['15px', { lineHeight: '23px' }],
        lead:  ['18px', { lineHeight: '27px' }],
        title: ['24px', { lineHeight: '30px', letterSpacing: '-0.018em' }],
        hero:  ['34px', { lineHeight: '40px', letterSpacing: '-0.026em' }],
        figure:['44px', { lineHeight: '46px', letterSpacing: '-0.032em' }],
      },
      borderRadius: { xs: '3px', sm: '5px', md: '7px', lg: '10px' },
      keyframes: {
        fill: { from: { transform: 'scaleX(0)' }, to: { transform: 'scaleX(1)' } },
        pulse7: { '0%,100%': { opacity: '1' }, '50%': { opacity: '0.35' } },
      },
      animation: {
        fill: 'fill 700ms cubic-bezier(0.22,0.8,0.3,1) both',
        pulse7: 'pulse7 1.8s ease-in-out infinite',
      },
    },
  },
  plugins: [],
}
