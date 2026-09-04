/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        'optiq': {
          '50': '#f0f7ff',
          '100': '#e0efff',
          '200': '#b9dffe',
          '300': '#7cc5fd',
          '400': '#36a7fa',
          '500': '#0c8deb',
          '600': '#006fc9',
          '700': '#0158a3',
          '800': '#064b86',
          '900': '#0b3f6f',
          '950': '#07274a',
        },
      },
    },
  },
  plugins: [],
}
