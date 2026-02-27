/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        claude: {
          bg: '#F8F9FB',
          surface: '#FFFFFF',
          surfaceHover: '#F0F1F4',
          surfaceMuted: '#F3F4F6',
          surfaceInset: '#EBEDF0',
          border: '#E0E2E7',
          borderLight: '#EBEDF0',
          text: '#1A1D23',
          textSecondary: '#6B7280',
          darkBg: '#0F1117',
          darkSurface: '#1A1D27',
          darkSurfaceHover: '#242830',
          darkSurfaceMuted: '#151820',
          darkSurfaceInset: '#0C0E14',
          darkBorder: '#2A2E38',
          darkBorderLight: '#1F232B',
          darkText: '#E4E5E9',
          darkTextSecondary: '#8B8FA3',
          accent: '#3B82F6',
          accentHover: '#2563EB',
          accentLight: '#60A5FA',
          accentMuted: 'rgba(59,130,246,0.10)',
        },
        primary: {
          DEFAULT: '#3B82F6',
          dark: '#2563EB'
        },
        secondary: {
          DEFAULT: '#6B7280',
          dark: '#2A2E38'
        }
      },
      boxShadow: {
        subtle: '0 1px 2px rgba(0,0,0,0.05)',
        card: '0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04)',
        elevated: '0 4px 12px rgba(0,0,0,0.1), 0 1px 3px rgba(0,0,0,0.04)',
        modal: '0 8px 30px rgba(0,0,0,0.16), 0 2px 8px rgba(0,0,0,0.08)',
        popover: '0 4px 20px rgba(0,0,0,0.12), 0 1px 4px rgba(0,0,0,0.05)',
        'glow-accent': '0 0 20px rgba(59,130,246,0.15)',
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        'fade-in-up': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in-down': {
          '0%': { opacity: '0', transform: 'translateY(-8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        'scale-in': {
          '0%': { opacity: '0', transform: 'scale(0.95)' },
          '100%': { opacity: '1', transform: 'scale(1)' },
        },
        shimmer: {
          '0%': { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(100%)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.2s ease-out',
        'fade-in-up': 'fade-in-up 0.25s ease-out',
        'fade-in-down': 'fade-in-down 0.2s ease-out',
        'scale-in': 'scale-in 0.2s ease-out',
        shimmer: 'shimmer 1.5s infinite',
      },
      transitionTimingFunction: {
        smooth: 'cubic-bezier(0.4, 0, 0.2, 1)',
      },
      typography: {
        DEFAULT: {
          css: {
            color: '#1A1D23',
            a: {
              color: '#3B82F6',
              '&:hover': {
                color: '#2563EB',
              },
            },
            code: {
              color: '#1A1D23',
              backgroundColor: 'rgba(224, 226, 231, 0.5)',
              padding: '0.2em 0.4em',
              borderRadius: '0.25rem',
              fontWeight: '400',
            },
            'code::before': {
              content: '""',
            },
            'code::after': {
              content: '""',
            },
            pre: {
              backgroundColor: '#F0F1F4',
              color: '#1A1D23',
              padding: '1em',
              borderRadius: '0.75rem',
              overflowX: 'auto',
            },
            blockquote: {
              borderLeftColor: '#3B82F6',
              color: '#6B7280',
            },
            h1: {
              color: '#1A1D23',
            },
            h2: {
              color: '#1A1D23',
            },
            h3: {
              color: '#1A1D23',
            },
            h4: {
              color: '#1A1D23',
            },
            strong: {
              color: '#1A1D23',
            },
          },
        },
        dark: {
          css: {
            color: '#E4E5E9',
            a: {
              color: '#60A5FA',
              '&:hover': {
                color: '#93BBFD',
              },
            },
            code: {
              color: '#E4E5E9',
              backgroundColor: 'rgba(42, 46, 56, 0.5)',
              padding: '0.2em 0.4em',
              borderRadius: '0.25rem',
              fontWeight: '400',
            },
            pre: {
              backgroundColor: '#1A1D27',
              color: '#E4E5E9',
              padding: '1em',
              borderRadius: '0.75rem',
              overflowX: 'auto',
            },
            blockquote: {
              borderLeftColor: '#3B82F6',
              color: '#8B8FA3',
            },
            h1: {
              color: '#E4E5E9',
            },
            h2: {
              color: '#E4E5E9',
            },
            h3: {
              color: '#E4E5E9',
            },
            h4: {
              color: '#E4E5E9',
            },
            strong: {
              color: '#E4E5E9',
            },
          },
        },
      },
    },
  },
  plugins: [
    require('@tailwindcss/typography'),
  ],
}
