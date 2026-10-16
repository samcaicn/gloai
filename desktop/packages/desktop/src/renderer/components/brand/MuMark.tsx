import React from 'react';
import classNames from 'classnames';
import styles from './Brand.module.css';

export type MuMarkProps = {
  size?: number;
  variant?: 'tile' | 'glyph';
  halo?: boolean;
  title?: string;
  className?: string;
};

const MuMark: React.FC<MuMarkProps> = ({ size = 32, variant = 'tile', halo = false, title, className }) => {
  const tile = variant === 'tile';
  return (
    <span
      className={classNames(styles.mark, halo && styles.halo, className)}
      style={{ width: size, height: size }}
      data-testid='mu-mark'
    >
      <svg
        width={size}
        height={size}
        viewBox='0 0 100 100'
        fill='none'
        role={title ? 'img' : undefined}
        aria-label={title}
        aria-hidden={title ? undefined : true}
        focusable='false'
      >
        <defs>
          <linearGradient id='aibuddy-mark-tile' x1='0' y1='0' x2='100' y2='100' gradientUnits='userSpaceOnUse'>
            <stop offset='0' stopColor='#5E7CFB' />
            <stop offset='0.5' stopColor='#4D6BFE' />
            <stop offset='1' stopColor='#3C57D4' />
          </linearGradient>
        </defs>
        {tile && <rect width='100' height='100' rx='27' fill={`url(#aibuddy-mark-tile)`} />}
        <text
          x='50'
          y='52'
          dominantBaseline='central'
          textAnchor='middle'
          fontFamily='Arial, Helvetica, sans-serif'
          fontWeight='800'
          fontSize={tile ? 21 : 18}
          fill={tile ? '#FFFFFF' : `url(#aibuddy-mark-tile)`}
        >
          AiBuddy
        </text>
      </svg>
    </span>
  );
};

export default MuMark;
