import React, { useEffect } from 'react';
import { Button } from '@arco-design/web-react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import MuMark from '@renderer/components/brand/MuMark';
import IlinkQrLogin from '@renderer/components/ilink/IlinkQrLogin';
import { useIlinkSession } from '@renderer/services/ilinkSession';

/**
 * 微信扫码页。
 *
 * 桌面端没有账号密码体系：身份就是微信（iLink）。扫码确认后主进程落盘登录态并
 * 打开消息推送，之后这台机器重启也不必再扫一次。
 *
 * 它不是必经之路：没人被送到这里，进来的人是自己点进来的（引导里那一步、设置页），
 * 所以「暂不登录」只是回到来处，不写任何标记。
 */
export default function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { phase } = useIlinkSession();
  const from = (location.state as { from?: string } | null)?.from ?? '/guid';

  useEffect(() => {
    if (phase !== 'signedIn') return;
    navigate(from, { replace: true });
  }, [phase, from, navigate]);

  return (
    <div
      className='h-100% w-100% flex flex-col items-center justify-center gap-20px px-24px py-32px bg-bg-1'
      data-testid='ilink-login-page'
    >
      <MuMark size={56} halo />
      <div className='text-center'>
        <h1 className='text-20px text-t-primary m-0 mb-8px'>{t('login.ilink.title')}</h1>
        <p className='text-13px text-t-secondary m-0'>{t('login.ilink.lead')}</p>
      </div>

      <IlinkQrLogin autoStart size={216} />

      <div className='flex flex-col items-center gap-6px'>
        <Button
          size='mini'
          type='text'
          onClick={() => navigate(from, { replace: true })}
          data-testid='ilink-login-skip'
        >
          {t('login.ilink.skip')}
        </Button>
        <span className='text-11px text-t-tertiary'>{t('login.ilink.skipHint')}</span>
      </div>
    </div>
  );
}
