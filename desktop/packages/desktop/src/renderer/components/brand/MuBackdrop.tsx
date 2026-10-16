import React, { useId } from 'react';
import classNames from 'classnames';
import styles from './Brand.module.css';

export type MuBackdropProps = {
  leaving?: boolean;
  className?: string;
};

const MuBackdrop: React.FC<MuBackdropProps> = ({ leaving = false, className }) => {
  const id = `aibuddy-backdrop-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  return (
    <span
      className={classNames(styles.backdrop, leaving && styles.backdropLeaving, className)}
      data-testid='mu-backdrop'
      data-state={leaving ? 'leaving' : 'shown'}
      aria-hidden='true'
    >
      <svg viewBox='0 0 100 100' fill='none' focusable='false' preserveAspectRatio='xMidYMid meet'>
        <defs>
          <linearGradient id={`${id}-fill`} x1='0' y1='20' x2='0' y2='90' gradientUnits='userSpaceOnUse'>
            <stop offset='0' className={styles.backdropFillTop} />
            <stop offset='1' className={styles.backdropFillBottom} />
          </linearGradient>
        </defs>
        <text
          x='50'
          y='56'
          dominantBaseline='central'
          textAnchor='middle'
          fontFamily='Arial, Helvetica, sans-serif'
          fontWeight='800'
          fontSize='43'
          fill={`url(#${id}-fill)`}
        >
          AiBuddy
        </text>
      </svg>
    </span>
  );
};

export default MuBackdrop;
